package org.example.platform;

import org.example.service.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.*;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.*;
import static org.example.platform.PlatformModels.*;

/** Prepare immutable source and both indexes, then commit one database pointer. No destructive replacement. */
@Service
public class KnowledgeIngestion {
    private final DocumentCatalog documents;
    private final SourceStorage sources;
    private final DocumentChunkService chunker;
    private final LexicalIndex lexical;
    private final VersionedVectorIndex vectors;
    private final KnowledgeFiles legacyFiles;
    private final ConsoleDocumentIndexService legacyVectors;
    public KnowledgeIngestion(DocumentCatalog documents,SourceStorage sources,DocumentChunkService chunker,
                              LexicalIndex lexical,VersionedVectorIndex vectors,KnowledgeFiles legacyFiles,ConsoleDocumentIndexService legacyVectors) {
        this.documents=documents;this.sources=sources;this.chunker=chunker;this.lexical=lexical;this.vectors=vectors;this.legacyFiles=legacyFiles;this.legacyVectors=legacyVectors;
    }
    public record UploadResult(DocumentInfo document,String action) {}
    public synchronized UploadResult upload(String datasetId,String folder,MultipartFile file) throws Exception {
        String name=file.getOriginalFilename();
        if(name==null||name.contains("/")||name.contains("\\")||name.contains(":"))throw PlatformCatalog.bad("文件名无效");
        String path=DocumentCatalog.normalizePath(folder==null||folder.isBlank()?name:folder.strip()+"/"+name);
        if(file.isEmpty()||file.getSize()>KnowledgeFiles.MAX_BYTES)throw PlatformCatalog.bad("文件需非空且不超过5MB");
        String content;
        try {content=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(file.getBytes())).toString();}
        catch(CharacterCodingException e){throw PlatformCatalog.bad("当前只支持UTF-8 Markdown/TXT");}
        return ingest(datasetId,path,content);
    }
    public synchronized UploadResult ingest(String datasetId,String path,String content) throws Exception {
        return ingest(datasetId,path,content,false);
    }
    public synchronized UploadResult reindex(String documentId) throws Exception {
        var info=documents.list(null).stream().filter(d->d.id().equals(documentId)).findFirst().orElseThrow(()->PlatformCatalog.missing("文档"));
        var active=documents.versions(documentId).stream().filter(v->v.version().equals(info.version())).findFirst().orElseThrow(()->PlatformCatalog.missing("活动文档版本"));
        return ingest(active.datasetId(),active.path(),sources.read(active).content(),true);
    }
    private UploadResult ingest(String datasetId,String path,String content,boolean force) throws Exception {
        if(content==null||content.isBlank()||content.getBytes(StandardCharsets.UTF_8).length>KnowledgeFiles.MAX_BYTES)throw PlatformCatalog.bad("文档正文为空或超过5MB");
        path=DocumentCatalog.normalizePath(path);
        var old=documents.currentByPath(datasetId,path);String hash=KnowledgeFiles.digest(content);boolean same=old.isPresent()&&old.get().contentHash().equals(hash);
        if(same&&!force) {
            var v=old.get();
            try {
                sources.read(v);
                if(v.chunkCount()>0&&lexical.count(v.version())==v.chunkCount()&&vectors.count(v.version())==v.chunkCount()) {documents.markHealthy(v);return result(v,"unchanged");}
            } catch(Exception ignored) { /* A repair builds a fresh version and retains this one until commit. */ }
        }
        var version=documents.prepare(datasetId,path,hash,content.length(),VersionedVectorIndex.COLLECTION);
        try {
            sources.write(version.documentId(),version.version(),content);
            var chunks=chunks(version,content);
            if(chunks.isEmpty())throw new IllegalArgumentException("文档没有可索引的内容");
            lexical.prepare(version.version(),chunks);
            vectors.prepare(chunks);
            if(lexical.count(version.version())!=chunks.size())throw new IllegalStateException("全文索引分块数不完整");
            documents.activate(version,chunks.size());
            return result(version,same?"repaired":old.isPresent()?"updated":"indexed");
        } catch(Exception error) {
            documents.fail(version,"索引准备失败（"+error.getClass().getSimpleName()+"），已有版本仍保留");
            if(error instanceof java.util.concurrent.CancellationException)throw error;
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"索引未完成，原版本仍可用；请查看索引状态后重试",error);
        }
    }
    private UploadResult result(DocumentVersion v,String action) {return new UploadResult(documents.list(v.datasetId()).stream().filter(d->d.id().equals(v.documentId())).findFirst().orElseThrow(),action);}
    List<PlatformChunk> chunks(DocumentVersion version,String content) {
        List<PlatformChunk> result=new ArrayList<>();
        for(var chunk:chunker.chunkDocument(content,version.path())) {
            int start=chunk.getStartIndex();
            if(start<0||chunk.getEndIndex()>content.length()||chunk.getEndIndex()-start!=chunk.getContent().length()
                    ||!content.substring(start,chunk.getEndIndex()).equals(chunk.getContent()))
                throw new IllegalStateException("分块不是连续原文，停止索引");
            result.add(chunk(version,result.size(),chunk.getTitle(),start,chunk.getContent()));
        }
        return List.copyOf(result);
    }
    private PlatformChunk chunk(DocumentVersion v,int index,String title,int start,String content) {
        return new PlatformChunk("D-"+KnowledgeFiles.digest(v.documentId()+":"+v.version()+":"+index).substring(0,20),v.documentId(),v.version(),v.datasetId(),v.path(),
                Objects.toString(title,"正文"),index,start,start+content.length(),content,null,null,null,"source");
    }
    /** Import the legacy baseline without new embedding calls or edits to the legacy collection. */
    public synchronized Map<String,Object> migrateLegacy() throws Exception {
        documents.recoverInterrupted();
        var stats=legacyVectors.statistics();int imported=0,skipped=0;List<Map<String,String>> failed=new ArrayList<>();
        for(var source:legacyFiles.sources()) {
            if(documents.currentByPath(PlatformCatalog.LEGACY_DATASET,source.name()).isPresent()){skipped++;continue;}
            DocumentVersion revision=null;
            try {
                var matches=ConsoleDocumentService.matchingSources(source.name(),stats.keySet());
                if(matches.size()!=1)throw new IllegalStateException("原索引来源不唯一或不存在");
                var rows=legacyVectors.snapshot(matches.get(0));
                revision=documents.prepare(PlatformCatalog.LEGACY_DATASET,source.name(),KnowledgeFiles.digest(source.content()),source.content().length(),VersionedVectorIndex.COLLECTION);
                sources.write(revision.documentId(),revision.version(),source.content());
                var gson=new com.google.gson.Gson();
                rows.sort(Comparator.comparingInt(row->gson.fromJson(row.get("metadata").toString(),com.google.gson.JsonObject.class).get("chunkIndex").getAsInt()));
                List<PlatformChunk> chunks=new ArrayList<>();List<List<Float>> embedded=new ArrayList<>();
                for(var row:rows) {
                    String original=KnowledgeFiles.originalExcerpt(source.content(),row.get("content").toString()).orElseThrow(()->new IllegalStateException("原分块无法定位来源"));
                    var metadata=gson.fromJson(row.get("metadata").toString(),com.google.gson.JsonObject.class);
                    String title=metadata.has("title")?metadata.get("title").getAsString():"正文";
                    chunks.add(chunk(revision,chunks.size(),title,source.content().indexOf(original),original));
                    embedded.add(((List<?>)row.get("vector")).stream().map(n->((Number)n).floatValue()).toList());
                }
                if(chunks.isEmpty())throw new IllegalStateException("原索引为空");
                lexical.prepare(revision.version(),chunks);vectors.prepareWithVectors(chunks,embedded);
                if(lexical.count(revision.version())!=chunks.size())throw new IllegalStateException("全文迁移数量不匹配");
                documents.activate(revision,chunks.size());imported++;
            } catch(Exception error) {
                if(revision!=null)documents.fail(revision,"原资料迁移未完成: "+error.getClass().getSimpleName());
                failed.add(Map.of("path",source.name(),"error",error.getClass().getSimpleName()+": "+Objects.toString(error.getMessage(),"")));
            }
        }
        return Map.of("imported",imported,"skipped",skipped,"failed",failed);
    }
}
