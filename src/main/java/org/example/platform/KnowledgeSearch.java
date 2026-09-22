package org.example.platform;

import org.example.service.*;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static org.example.platform.PlatformModels.*;

/** One retrieval implementation for console and Agent tools; the agent controls any follow-up search. */
@Service
public class KnowledgeSearch {
    private static final Pattern ORDINAL_SECTION = Pattern.compile("第\\s*([0-9零〇一二两三四五六七八九十百千万]+)\\s*(卷|章|篇|节|册|部|部分)");
    private static final int CONTEXT_WINDOW_CHARS = 6000;
    private static final int CONTEXT_BOUNDARY_SNAP_CHARS = 400;
    private final PlatformCatalog catalog;
    private final DocumentCatalog documents;
    private final SourceStorage sources;
    private final LexicalIndex lexical;
    private final VersionedVectorIndex vectors;
    private final RerankService reranker;
    private final DocumentReadingService reader;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private ChatModelFactory models;
    public KnowledgeSearch(PlatformCatalog catalog,DocumentCatalog documents,SourceStorage sources,LexicalIndex lexical,
                           VersionedVectorIndex vectors,RerankService reranker,DocumentReadingService reader) {
        this.catalog=catalog;this.documents=documents;this.sources=sources;this.lexical=lexical;this.vectors=vectors;this.reranker=reranker;this.reader=reader;
    }
    public record Scope(List<String> knowledgeBaseIds,Set<String> datasetIds,List<DocumentVersion> documents) {}
    public record SearchResult(String query,String mode,String status,boolean degraded,List<String> notices,
                               int candidateCount,List<PlatformChunk> candidates,List<PlatformChunk> documents,
                               long vectorMs,long keywordMs,long rerankMs,long totalMs) {}
    public Scope scope(List<String> knowledgeBaseIds) {
        var ids=PlatformCatalog.references(knowledgeBaseIds,30);var datasets=catalog.resolveDatasets(ids);
        return new Scope(ids,datasets,documents.active(datasets));
    }
    public SearchResult search(Scope scope,String query,String mode,int candidateTopK,int topK,boolean rerank) throws Exception {
        return search(scope,query,mode,candidateTopK,topK,rerank,true);
    }
    private SearchResult search(Scope scope,String query,String mode,int candidateTopK,int topK,boolean rerank,boolean allowCrossLanguageRewrite) throws Exception {
        query=PlatformCatalog.required(query,8000,"查询");
        if(!Set.of("semantic","keyword","hybrid").contains(mode))throw PlatformCatalog.bad("检索模式无效");
        if(topK<1||topK>10||candidateTopK<topK||candidateTopK>100)throw PlatformCatalog.bad("候选数或返回数无效");
        String retrievalQuery=expandSectionAliases(query);
        long began=System.nanoTime(),vectorMs=0,keywordMs=0,rerankMs=0;
        List<String> notices=new ArrayList<>();List<PlatformChunk> semantic=List.of(),text=List.of();
        var versions=scope.documents().stream().map(DocumentVersion::version).toList();
        if(versions.isEmpty())return new SearchResult(query,mode,"no_results",false,List.of("当前知识范围没有已生效文档"),0,List.of(),List.of(),0,0,0,elapsed(began));
        if(!mode.equals("keyword")) {
            long start=System.nanoTime();
            try {semantic=vectors.search(retrievalQuery,versions,candidateTopK);}
            catch(Exception error){if(mode.equals("semantic"))throw error;notices.add("向量检索不可用，保留独立全文候选");}
            vectorMs=elapsed(start);
        }
        if(!mode.equals("semantic")) {
            long start=System.nanoTime();
            try {text=lexical.search(retrievalQuery,versions,candidateTopK,false);}
            catch(Exception error){if(mode.equals("keyword"))throw error;notices.add("全文检索不可用，保留向量候选");}
            keywordMs=elapsed(start);
        }
        // Both retrieval channels nominate candidates. Do not discard one channel's hits before reranking.
        var candidates=mode.equals("hybrid")?fuse(semantic,text,rerank?candidateTopK*2:candidateTopK):mode.equals("keyword")?text:semantic;
        // Source check before model context. Preserve failures as traceable evidence, never silently substitute another source.
        List<PlatformChunk> valid=new ArrayList<>();
        Map<String,DocumentReadingService.Source> loaded=new HashMap<>();
        for(var candidate:candidates) {
            var version=scope.documents().stream().filter(v->v.version().equals(candidate.version())&&v.documentId().equals(candidate.documentId())).findFirst();
            if(version.isEmpty()){notices.add("检索结果越出本次版本范围，已拒绝");continue;}
            var source=loaded.get(candidate.version());
            if(source==null){source=sources.read(version.get());loaded.put(candidate.version(),source);}
            if(candidate.start()<0||candidate.end()>source.content().length()||candidate.end()<candidate.start()
                    ||!source.content().substring(candidate.start(),candidate.end()).equals(candidate.content())) {
                notices.add("文档 "+candidate.sourceFile()+" 的索引片段与原文不一致，需修复索引");continue;
            }
            valid.add(candidate);
        }
        List<PlatformChunk> selected=valid.stream().limit(topK).toList();
        if(rerank&&!valid.isEmpty()) {
            long start=System.nanoTime();
            try {
                Map<String,PlatformChunk> byId=new LinkedHashMap<>();List<VectorSearchService.SearchResult> inputs=new ArrayList<>();
                for(var c:valid){byId.put(c.id(),c);var r=new VectorSearchService.SearchResult();r.setId(c.id());r.setContent(c.retrievalText());inputs.add(r);}
                selected=reranker.rerank(retrievalQuery,inputs,Math.min(topK,inputs.size())).stream()
                        .map(r->{var c=byId.get(r.getId());return c.scored(c.vectorDistance(),c.keywordScore(),r.getRerankScore(),c.method());}).toList();
            }catch(Exception error){notices.add("精排失败，保留候选原始排序");}
            rerankMs=elapsed(start);
        }
        String status=selected.isEmpty()?(notices.isEmpty()?"no_results":"error"):"candidates";
        var primary=new SearchResult(query,mode,status,!notices.isEmpty(),List.copyOf(notices),candidates.size(),List.copyOf(valid),selected,vectorMs,keywordMs,rerankMs,elapsed(began));
        if(!allowCrossLanguageRewrite||models==null||!needsCrossLanguageRewrite(query,selected))return primary;
        String target=dominantLanguage(selected)==Language.LATIN?"English":"Chinese";
        String rewritten=models.rewrite("""
                Translate this knowledge-search query into %s and return only one concise search query.
                Preserve every error code, version, number, identifier and negative condition. Translate names only when a standard target-language form is known. Do not answer the question or add facts.
                Query: %s
                """.formatted(target,query));
        rewritten=QueryRewriteGuard.preserve(query,rewritten);
        if(rewritten.equals(query))return primary;
        // The translated branch only nominates candidates. The merged bilingual pool is
        // reranked once below, so reranking this branch separately adds cost without
        // affecting the final order.
        var translated=search(scope,rewritten,mode,candidateTopK,topK,false,false);
        return mergeCrossLanguage(primary,translated,query,rewritten,mode,topK,rerank,began);
    }

    enum Language { CJK,LATIN,UNKNOWN }
    static Language dominantLanguage(String value) {
        int cjk=0,latin=0;
        for(int i=0;i<value.length();i++) {
            char ch=value.charAt(i);
            if(Character.UnicodeScript.of(ch)==Character.UnicodeScript.HAN)cjk++;
            else if((ch>='A'&&ch<='Z')||(ch>='a'&&ch<='z'))latin++;
        }
        if(cjk>=4&&cjk>=latin)return Language.CJK;
        if(latin>=8&&latin>cjk*2)return Language.LATIN;
        return Language.UNKNOWN;
    }
    static Language dominantLanguage(List<PlatformChunk> chunks) {
        StringBuilder sample=new StringBuilder();
        chunks.stream().limit(5).forEach(c->sample.append(c.content()).append('\n'));
        return dominantLanguage(sample.toString());
    }
    static boolean needsCrossLanguageRewrite(String query,List<PlatformChunk> selected) {
        if(selected.isEmpty())return false;
        Language question=dominantLanguage(query),documents=dominantLanguage(selected);
        if(question==Language.UNKNOWN||documents==Language.UNKNOWN||question==documents)return false;
        Double score=selected.get(0).rerankScore();
        // Cross-language recovery is deliberately bounded. A moderate wrong-language
        // score caused confirmed misses, while rewriting already-confident results
        // increased latency and destabilized first-place ranking across both corpora.
        return score==null||score<0.35;
    }
    private SearchResult mergeCrossLanguage(SearchResult primary,SearchResult translated,String original,String rewritten,
                                             String mode,int topK,boolean rerank,long began) {
        List<String> notices=new ArrayList<>(primary.notices());
        notices.addAll(translated.notices());
        notices.add("检测到问题与资料语言不一致，已使用一次受控翻译查询并合并候选");
        List<PlatformChunk> candidates=fuse(primary.candidates(),translated.candidates(),Math.min(100,primary.candidates().size()+translated.candidates().size()));
        List<PlatformChunk> selected=candidates.stream().limit(topK).toList();long extraRerankMs=0;
        if(rerank&&!candidates.isEmpty()) {
            long start=System.nanoTime();
            try {
                Map<String,PlatformChunk> byId=new LinkedHashMap<>();List<VectorSearchService.SearchResult> inputs=new ArrayList<>();
                for(var c:candidates){byId.put(c.id(),c);var r=new VectorSearchService.SearchResult();r.setId(c.id());r.setContent(c.retrievalText());inputs.add(r);}
                selected=reranker.rerank(rewritten,inputs,Math.min(topK,inputs.size())).stream()
                        .map(r->{var c=byId.get(r.getId());return c.scored(c.vectorDistance(),c.keywordScore(),r.getRerankScore(),"multilingual");}).toList();
            }catch(Exception error){notices.add("跨语言合并精排失败，保留融合候选排序");}
            extraRerankMs=elapsed(start);
        }
        String status=selected.isEmpty()?"no_results":"candidates";
        return new SearchResult(original,mode,status,primary.degraded()||translated.degraded(),List.copyOf(new LinkedHashSet<>(notices)),
                candidates.size(),List.copyOf(candidates),List.copyOf(selected),primary.vectorMs()+translated.vectorMs(),
                primary.keywordMs()+translated.keywordMs(),primary.rerankMs()+translated.rerankMs()+extraRerankMs,elapsed(began));
    }

    /**
     * Keep the user's wording, but add language-neutral section aliases when a query
     * names an ordinal section. This helps English documents answer Chinese queries
     * such as "第二十三卷" without a document-specific dictionary or re-indexing.
     */
    static String expandSectionAliases(String query) {
        Matcher matcher=ORDINAL_SECTION.matcher(query);
        if(!matcher.find())return query;
        int number=parseSectionNumber(matcher.group(1));
        if(number<1||number>999)return query;
        String roman=roman(number);
        return query+" Book "+number+" Book "+roman+" Chapter "+number;
    }

    static int parseSectionNumber(String value) {
        if(value.chars().allMatch(Character::isDigit))return Integer.parseInt(value);
        Map<Character,Integer> digits=Map.ofEntries(Map.entry('零',0),Map.entry('〇',0),Map.entry('一',1),Map.entry('两',2),
                Map.entry('二',2),Map.entry('三',3),Map.entry('四',4),Map.entry('五',5),Map.entry('六',6),Map.entry('七',7),
                Map.entry('八',8),Map.entry('九',9));
        int total=0,section=0,number=0;
        for(int i=0;i<value.length();i++) {
            char c=value.charAt(i);
            if(digits.containsKey(c)) { number=digits.get(c); continue; }
            int unit=c=='十'?10:c=='百'?100:c=='千'?1000:c=='万'?10000:0;
            if(unit==0)continue;
            if(number==0)number=1;
            if(unit==10000){section=(section+number)*unit;total+=section;section=0;}
            else section+=number*unit;
            number=0;
        }
        return total+section+number;
    }

    static String roman(int number) {
        int[] values={1000,900,500,400,100,90,50,40,10,9,5,4,1};
        String[] symbols={"M","CM","D","CD","C","XC","L","XL","X","IX","V","IV","I"};
        StringBuilder out=new StringBuilder();
        for(int i=0;i<values.length;i++)while(number>=values[i]){out.append(symbols[i]);number-=values[i];}
        return out.toString();
    }
    static List<PlatformChunk> fuse(List<PlatformChunk> a,List<PlatformChunk> b,int limit) {
        Map<String,PlatformChunk> docs=new LinkedHashMap<>();Map<String,Double> scores=new HashMap<>();
        for(var list:List.of(a,b))for(int i=0;i<list.size();i++) {
            var hit=list.get(i);var old=docs.get(hit.id());
            docs.put(hit.id(),old==null?hit:old.scored(old.vectorDistance()!=null?old.vectorDistance():hit.vectorDistance(),hit.keywordScore()!=null?hit.keywordScore():old.keywordScore(),null,"hybrid"));
            scores.merge(hit.id(),1.0/(60+i+1),Double::sum);
        }
        return docs.values().stream().sorted(Comparator.comparingDouble((PlatformChunk c)->scores.get(c.id())).reversed().thenComparing(PlatformChunk::id)).limit(limit).toList();
    }
    public List<Map<String,Object>> findDocuments(Scope scope,String query) {
        query=PlatformCatalog.required(query,500,"文件名或路径").toLowerCase(Locale.ROOT);String term=query;
        return scope.documents().stream().filter(v->v.path().toLowerCase(Locale.ROOT).contains(term)).limit(20)
                .map(v->Map.<String,Object>of("documentId",v.documentId(),"version",v.version(),"sourceFile",v.path(),"contentLength",v.contentLength())).toList();
    }
    public List<PlatformChunk> searchText(Scope scope,String query,String documentId,int topK) throws Exception {
        var versions=scope.documents().stream().filter(v->documentId==null||documentId.isBlank()||documentId.equals(v.documentId())).map(DocumentVersion::version).toList();
        var hits=lexical.search(PlatformCatalog.required(query,8000,"查询"),versions,topK,false);
        for(var hit:hits) {
            var original=source(scope,hit.documentId(),hit.version()).content();
            if(hit.start()<0||hit.end()<hit.start()||hit.end()>original.length()||!original.substring(hit.start(),hit.end()).equals(hit.content()))
                throw new IllegalStateException("全文索引片段与原文不一致，请修复索引");
        }
        return hits;
    }
    public List<PlatformChunk> chunks(Scope scope,int limit) throws Exception {
        return lexical.list(scope.documents().stream().map(DocumentVersion::version).toList(),limit);
    }
    public List<PlatformChunk> evaluationChunks(Scope scope) throws Exception {
        List<PlatformChunk> result=new ArrayList<>();
        for(var document:scope.documents())result.addAll(lexical.list(List.of(document.version()),Integer.MAX_VALUE));
        return List.copyOf(result);
    }
    /** Small indexed chunks locate the answer; merged neighboring source windows supply reading context. */
    public List<AgentToolRegistry.Evidence> contextWindows(Scope scope,List<PlatformChunk> hits) throws Exception {
        List<AgentToolRegistry.Evidence> result=new ArrayList<>();
        Map<String,List<PlatformChunk>> groups=new LinkedHashMap<>();
        hits.forEach(c->groups.computeIfAbsent(c.documentId()+":"+c.version(),k->new ArrayList<>()).add(c));
        for(var group:groups.values()) {
            var first=group.get(0);var original=source(scope,first.documentId(),first.version());
            List<int[]> ranges=new ArrayList<>();
            for(var hit:group)ranges.add(contextRange(original,hit));
            ranges.sort(Comparator.comparingInt(r->r[0]));List<int[]> merged=new ArrayList<>();
            for(var r:ranges) {
                if(!merged.isEmpty()&&r[0]<=merged.get(merged.size()-1)[1])merged.get(merged.size()-1)[1]=Math.max(r[1],merged.get(merged.size()-1)[1]);
                else merged.add(r.clone());
            }
            for(var r:merged) {
                if(r[0]>0&&Character.isLowSurrogate(original.content().charAt(r[0])))r[0]--;
                if(r[1]<original.content().length()&&Character.isLowSurrogate(original.content().charAt(r[1])))r[1]++;
                String id="D-"+KnowledgeFiles.digest(first.documentId()+":"+first.version()+":"+r[0]+":"+r[1]).substring(0,20);
                result.add(new AgentToolRegistry.Evidence(id,first.documentId(),first.version(),first.sourceFile(),first.title(),r[0],r[1],original.content().substring(r[0],r[1]),"document"));
            }
        }
        return List.copyOf(result);
    }
    private int[] contextRange(DocumentReadingService.Source source,PlatformChunk hit) {
        int lower=0,upper=source.content().length();
        var section=reader.sections(source).stream()
                .filter(s->s.start()<=hit.start()&&s.end()>=hit.end())
                .min(Comparator.comparingInt(s->s.end()-s.start()));
        if(section.isPresent()){lower=section.get().start();upper=section.get().end();}
        int available=Math.max(0,CONTEXT_WINDOW_CHARS-(hit.end()-hit.start()));
        int start=Math.max(lower,hit.start()-available/2);
        int end=Math.min(upper,start+CONTEXT_WINDOW_CHARS);
        start=Math.max(lower,end-CONTEXT_WINDOW_CHARS);
        start=snapStart(source.content(),start,hit.start());
        end=snapEnd(source.content(),hit.end(),end);
        return new int[]{start,end};
    }
    private static int snapStart(String content,int target,int hitStart) {
        int lf=content.indexOf("\n\n",target),crlf=content.indexOf("\r\n\r\n",target);
        int boundary=minPositive(lf<0?-1:lf+2,crlf<0?-1:crlf+4);
        return boundary>=0&&boundary<=hitStart?boundary:target;
    }
    private static int snapEnd(String content,int hitEnd,int target) {
        int lf=content.lastIndexOf("\n\n",target),crlf=content.lastIndexOf("\r\n\r\n",target);
        int boundary=Math.max(lf<0?-1:lf+2,crlf<0?-1:crlf+4);
        // A distant previous boundary can discard most of the trailing context when a
        // Markdown paragraph is long. Align only when the boundary is close to the cap.
        return boundary>=hitEnd&&target-boundary<=CONTEXT_BOUNDARY_SNAP_CHARS?boundary:target;
    }
    private static int minPositive(int first,int second) {
        if(first<0)return second;if(second<0)return first;return Math.min(first,second);
    }
    private DocumentReadingService.Source source(Scope scope,String documentId,String version) throws Exception {
        var match=scope.documents().stream().filter(v->v.documentId().equals(documentId)&&v.version().equals(version)).findFirst().orElseThrow(()->PlatformCatalog.missing("本次范围内的来源版本"));
        return sources.read(match);
    }
    public List<DocumentReadingService.Section> sections(Scope scope,String documentId,String version) throws Exception {return reader.sections(source(scope,documentId,version));}
    public DocumentReadingService.Window read(Scope scope,String documentId,String version,String section,Integer offset,Integer maxChars) throws Exception {
        return reader.read(source(scope,documentId,version),version,section,offset,maxChars);
    }
    private static long elapsed(long start){return (System.nanoTime()-start)/1_000_000;}
}
