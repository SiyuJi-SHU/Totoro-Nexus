package org.example.platform;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.cjk.CJKAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.store.*;
import org.apache.lucene.util.BytesRef;
import org.example.service.KnowledgeFiles;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Persistent independent BM25 index; a Milvus outage does not disable these reads. */
@Service
public class LexicalIndex {
    private final Directory directory;
    private final Analyzer analyzer = new CJKAnalyzer();
    private final IndexWriter writer;
    private final SearcherManager searchers;
    public LexicalIndex(KnowledgeFiles files) throws IOException {
        Path root=files.root().resolve(".platform").resolve("lexical");
        for(Path p=root;p!=null&&p.startsWith(files.root());p=p.getParent())if(Files.isSymbolicLink(p))throw new IOException("索引目录不能是符号链接");
        Files.createDirectories(root);directory=FSDirectory.open(root);
        writer=new IndexWriter(directory,new IndexWriterConfig(analyzer));writer.commit();
        searchers=new SearcherManager(writer,null);
    }
    public synchronized void prepare(String version,List<PlatformChunk> chunks) throws IOException {
        List<Document> docs=new ArrayList<>();
        for(var chunk:chunks) {
            Document d=new Document();
            field(d,"id",chunk.id());field(d,"version",version);field(d,"documentId",chunk.documentId());field(d,"datasetId",chunk.datasetId());
            d.add(new TextField("filename",chunk.sourceFile(),Field.Store.YES));
            d.add(new TextField("title",Objects.toString(chunk.title(),"正文"),Field.Store.YES));
            d.add(new TextField("content",chunk.content(),Field.Store.YES));
            d.add(new StoredField("start",chunk.start()));d.add(new StoredField("end",chunk.end()));d.add(new StoredField("chunkIndex",chunk.chunkIndex()));
            docs.add(d);
        }
        writer.updateDocuments(new Term("version",version),docs);writer.commit();searchers.maybeRefreshBlocking();
    }
    private static void field(Document d,String name,String value){d.add(new StringField(name,value,Field.Store.YES));}
    public int count(String version) throws IOException {
        var searcher=searchers.acquire();try{return searcher.count(new TermQuery(new Term("version",version)));}finally{searchers.release(searcher);}
    }
    public List<PlatformChunk> search(String query,Collection<String> versions,int limit,boolean filenameOnly) throws IOException {
        if(versions.isEmpty())return List.of();
        LinkedHashSet<String> terms=new LinkedHashSet<>();
        try(var stream=analyzer.tokenStream("content",query)) {
            var attr=stream.addAttribute(CharTermAttribute.class);stream.reset();
            while(stream.incrementToken()&&terms.size()<64)terms.add(attr.toString());stream.end();
        }
        if(terms.isEmpty())return List.of();
        var matches=new BooleanQuery.Builder();
        for(String term:terms) {
            matches.add(new BoostQuery(new TermQuery(new Term("filename",term)),3),BooleanClause.Occur.SHOULD);
            if(!filenameOnly){matches.add(new BoostQuery(new TermQuery(new Term("title",term)),2),BooleanClause.Occur.SHOULD);matches.add(new TermQuery(new Term("content",term)),BooleanClause.Occur.SHOULD);}
        }
        matches.setMinimumNumberShouldMatch(1);
        Query scope=new TermInSetQuery("version",versions.stream().map(BytesRef::new).toList());
        Query combined=new BooleanQuery.Builder().add(scope,BooleanClause.Occur.FILTER).add(matches.build(),BooleanClause.Occur.MUST).build();
        var searcher=searchers.acquire();
        try {
            List<PlatformChunk> result=new ArrayList<>();
            for(var hit:searcher.search(combined,Math.max(1,Math.min(limit,100))).scoreDocs) {
                Document d=searcher.storedFields().document(hit.doc);
                result.add(new PlatformChunk(d.get("id"),d.get("documentId"),d.get("version"),d.get("datasetId"),d.get("filename"),d.get("title"),
                        d.getField("chunkIndex").numericValue().intValue(),d.getField("start").numericValue().intValue(),d.getField("end").numericValue().intValue(),
                        d.get("content"),null,(double)hit.score,null,filenameOnly?"filename":"keyword"));
            }
            return List.copyOf(result);
        } finally {searchers.release(searcher);}
    }
    public List<PlatformChunk> list(Collection<String> versions,int limit) throws IOException {
        if(versions.isEmpty())return List.of();
        Query scope=new TermInSetQuery("version",versions.stream().map(BytesRef::new).toList());
        var searcher=searchers.acquire();
        try {
            List<PlatformChunk> result=new ArrayList<>();
            ScoreDoc after=null;
            while(result.size()<limit) {
            var page=searcher.searchAfter(after,scope,Math.min(500,limit-result.size()));
            if(page.scoreDocs.length==0)break;
            for(var hit:page.scoreDocs) {
                Document d=searcher.storedFields().document(hit.doc);
                result.add(new PlatformChunk(d.get("id"),d.get("documentId"),d.get("version"),d.get("datasetId"),d.get("filename"),d.get("title"),
                        d.getField("chunkIndex").numericValue().intValue(),d.getField("start").numericValue().intValue(),d.getField("end").numericValue().intValue(),
                        d.get("content"),null,null,null,"source"));
            }
            after=page.scoreDocs[page.scoreDocs.length-1];
            }
            return List.copyOf(result);
        } finally {searchers.release(searcher);}
    }
    @PreDestroy public void close() throws IOException {searchers.close();writer.close();directory.close();analyzer.close();}
}
