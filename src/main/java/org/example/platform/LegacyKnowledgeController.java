package org.example.platform;

import org.example.service.ConsoleDocumentService;
import org.example.service.RetrievalTrace;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.*;
import java.util.*;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/console")
public class LegacyKnowledgeController {
    private final KnowledgeSearch search;private final KnowledgeIngestion ingestion;private final DocumentCatalog documents;private final SourceStorage sources;
    public LegacyKnowledgeController(KnowledgeSearch search,KnowledgeIngestion ingestion,DocumentCatalog documents,SourceStorage sources){this.search=search;this.ingestion=ingestion;this.documents=documents;this.sources=sources;}
    public record Recall(String query,Integer topK,Integer candidateTopK,Boolean rerankEnabled){}
    @PostMapping("/recall/test") public Object recall(@RequestBody Recall request)throws Exception {
        if(request==null)throw PlatformCatalog.bad("查询不能为空");String query=PlatformCatalog.required(request.query(),4000,"查询");
        int candidates=request.candidateTopK()==null?10:request.candidateTopK(),top=request.topK()==null?3:request.topK();
        if(top<1||top>10||candidates<top||candidates>100)throw PlatformCatalog.bad("检索候选数或返回数无效");
        var result=search.search(search.scope(List.of(PlatformCatalog.LEGACY_KNOWLEDGE)),query,"semantic",candidates,top,!Boolean.FALSE.equals(request.rerankEnabled()));
        List<RetrievalTrace.Document> docs=new ArrayList<>();for(int i=0;i<result.documents().size();i++){var d=result.documents().get(i);int original=java.util.stream.IntStream.range(0,result.candidates().size()).filter(n->result.candidates().get(n).id().equals(d.id())).findFirst().orElse(-1);docs.add(new RetrievalTrace.Document(i+1,d.sourceFile(),d.title(),d.chunkIndex(),d.vectorDistance()==null?0:d.vectorDistance().floatValue(),d.rerankScore(),d.content(),original<0?null:original+1));}
        return new RetrievalTrace(query,"console-recall-test","qwen3.7-text-embedding-flash","gte-rerank-v2",result.candidateCount(),docs.size(),result.vectorMs(),result.rerankMs(),result.totalMs(),!Boolean.FALSE.equals(request.rerankEnabled()),result.documents().stream().anyMatch(d->d.rerankScore()!=null),result.degraded(),String.join("; ",result.notices()),false,docs);
    }
    @GetMapping("/documents") public Object list(){return documents.list(PlatformCatalog.LEGACY_DATASET).stream().map(d->new ConsoleDocumentService.DocumentInfo(d.path(),d.contentLength(),java.time.Instant.parse(d.updatedAt()).toEpochMilli(),(long)d.chunkCount(),d.status(),d.path().substring(d.path().lastIndexOf('/')+1),d.version()!=null)).toList();}
    private PlatformModels.DocumentVersion current(String name){return documents.currentByPath(PlatformCatalog.LEGACY_DATASET,DocumentCatalog.normalizePath(name)).orElseThrow(()->PlatformCatalog.missing("文档"));}
    @GetMapping("/documents/preview") public Object preview(@RequestParam String name)throws Exception{var v=current(name);return Map.of("name",name,"content",sources.read(v).content(),"version",v.version());}
    @GetMapping("/documents/download") public ResponseEntity<?> download(@RequestParam String name)throws Exception{var v=current(name);return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(v.path().substring(v.path().lastIndexOf('/')+1),StandardCharsets.UTF_8).build().toString()).body(sources.read(v).content());}
    @PostMapping("/documents/upload") public Object upload(@RequestParam MultipartFile file,@RequestParam(defaultValue="existing-data") String datasetId,@RequestParam(defaultValue="") String folder)throws Exception{return ingestion.upload(datasetId,folder,file);}
    @DeleteMapping("/documents") public Object delete(@RequestParam String name){documents.remove(current(name).documentId());return Map.of("deleted",true);}
}
