package org.example.service;

import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** Reads persisted evaluation artifacts; never starts a model evaluation from a page view. */
@Service
public class ConsoleEvaluationService {
    private final Path root;
    private final ObjectMapper mapper=new ObjectMapper();
    private List<Entry> cache=List.of();
    private long loadedAt;
    public ConsoleEvaluationService(@Value("${console.eval.path:../eval}") String path){root=Path.of(path).toAbsolutePath().normalize();}
    private record Entry(Path path,Map<String,Object> summary){}
    private static Object value(JsonNode n,String field){JsonNode v=n.path(field);if(v.isMissingNode()||v.isNull())return null;if(v.isNumber())return v.numberValue();if(v.isBoolean())return v.booleanValue();return v.asText();}
    static Map<String,Object> gate(Double recall){String s=recall==null?"unavailable":Math.round(recall*10000)/100.0>=92.86?"pass":"fail";return Map.of("status",s,"recallAt1Target",.9286,"reason",s.equals("unavailable")?"未记录Recall@1":s.equals("pass")?"Recall@1达到92.86%门禁":"Recall@1低于92.86%门禁");}
    private Entry parse(Path p)throws Exception {
        if(Files.isSymbolicLink(p)||Files.size(p)>2_000_000||!p.toRealPath().startsWith(root.toRealPath()))return null;
        JsonNode n=mapper.readTree(p.toFile()),s=n.path("summary");
        if(!s.path("recallAt1").isNumber()||!s.path("totalCases").isNumber()||s.path("totalCases").asInt()<1)return null;
        double recall=s.path("recallAt1").asDouble();if(!Double.isFinite(recall)||recall<0||recall>1)return null;
        String relative=root.relativize(p).toString().replace('\\','/');
        String id=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(relative.getBytes(StandardCharsets.UTF_8))).substring(0,24);
        String time=n.path("generatedAt").asText();String source="report";
        try{time=OffsetDateTime.parse(time).toInstant().toString();}catch(Exception e){try{time=Instant.parse(time).toString();}catch(Exception ignored){time=Files.getLastModifiedTime(p).toInstant().toString();source="file_modified";}}
        Map<String,Object> m=new LinkedHashMap<>();
        m.put("id",id);m.put("name",p.getFileName().toString().replaceFirst("\\.json$",""));m.put("sourcePath",relative);m.put("generatedAt",time);m.put("timeSource",source);
        m.put("totalCases",s.path("totalCases").asInt());m.put("recallAt1",recall);m.put("recallAtK",value(s,"recallAtK"));m.put("resultTopK",value(n,"resultTopK"));
        m.put("recallAt3",n.path("resultTopK").asInt(-1)==3?value(s,"recallAtK"):value(s,"recallAt3"));
        m.put("mrr",value(s,"mrr"));m.put("averageLatencyMs",value(s,"averageLatencyMs"));m.put("embeddingModel",value(n,"embeddingModel"));m.put("rerankModel",value(n,"rerankModel"));m.put("gate",gate(recall));
        m.put("detailUrl","/api/console/evaluation/"+id+"/detail");m.put("downloadUrl","/api/console/evaluation/"+id+"/download");
        return new Entry(p,m);
    }
    private synchronized List<Entry> entries()throws Exception {
        if(System.currentTimeMillis()-loadedAt<10000)return cache;
        List<Entry> next=new ArrayList<>();
        if(Files.isDirectory(root))try(var paths=Files.walk(root,6)){
            for(Path p:paths.filter(f->Files.isRegularFile(f)&&f.toString().endsWith(".json")&&f.toString().replace('\\','/').contains("/results/")).toList()){
                try{var e=parse(p);if(e!=null)next.add(e);}catch(Exception ignored){/* Non-evaluation and malformed artifacts are excluded. */}
            }
        }
        next.sort(Comparator.comparing(e->e.summary().get("generatedAt").toString(),Comparator.reverseOrder()));cache=List.copyOf(next);loadedAt=System.currentTimeMillis();return cache;
    }
    public Map<String,Object> history(int limit)throws Exception {var all=entries();return Map.of("available",Files.isDirectory(root),"total",all.size(),"scope","已保存的结构化RAG评测；未解析的文件不计入历史","records",all.stream().limit(Math.max(1,Math.min(100,limit))).map(Entry::summary).toList());}
    private Entry find(String id)throws Exception {return entries().stream().filter(e->e.summary().get("id").equals(id)).findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"评测记录不存在"));}
    public Path download(String id)throws Exception {Path p=find(id).path();if(Files.isSymbolicLink(p)||!p.toRealPath().startsWith(root.toRealPath()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"评测路径不合法");return p;}
    public Map<String,Object> detail(String id)throws Exception {Entry e=find(id);String raw=Files.readString(download(id));JsonNode n=mapper.readTree(raw);return Map.of("summary",e.summary(),"cases",n.path("results").isArray()?n.path("results"):List.of(),"rawContent",raw,"format","json");}
}
