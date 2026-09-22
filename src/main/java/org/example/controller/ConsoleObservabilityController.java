package org.example.controller;

import io.micrometer.core.instrument.*;
import org.example.service.ConsoleEvaluationService;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.core.io.FileSystemResource;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.nio.charset.StandardCharsets;

@RestController @RequestMapping("/api/console")
public class ConsoleObservabilityController {
    private final MeterRegistry registry;
    private final ConsoleEvaluationService evaluations;
    public ConsoleObservabilityController(MeterRegistry registry,ConsoleEvaluationService evaluations){this.registry=registry;this.evaluations=evaluations;}
    @GetMapping("/evaluation/history") public Object history(@RequestParam(defaultValue="30") int limit)throws Exception{return evaluations.history(limit);}
    @GetMapping("/evaluation/{id}/detail") public Object detail(@PathVariable String id)throws Exception{return evaluations.detail(id);}
    @GetMapping("/evaluation/{id}/download") public ResponseEntity<?> download(@PathVariable String id)throws Exception{var p=evaluations.download(id);return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(p.getFileName().toString(),StandardCharsets.UTF_8).build().toString()).body(new FileSystemResource(p));}
    @GetMapping("/metrics/realtime") public Map<String,Object> realtime(){
        Map<String,Object> out=new LinkedHashMap<>();var runtime=ManagementFactory.getRuntimeMXBean();var heap=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        out.put("sampledAt",Instant.now().toString());out.put("scope","当前进程启动后的累计观测，不包含控制台请求；重启后重新计数");out.put("processStartedAt",Instant.ofEpochMilli(runtime.getStartTime()).toString());out.put("uptimeSeconds",runtime.getUptime()/1000);
        Map<String,double[]> endpoints=new TreeMap<>();long count=0,errors=0;double total=0;
        for(var t:registry.find("http.server.requests").timers()){
            String uri=t.getId().getTag("uri"),method=t.getId().getTag("method"),status=t.getId().getTag("status");
            if(uri==null||!uri.startsWith("/api/")||uri.startsWith("/api/console"))continue;
            long c=t.count(),error=status!=null&&status.startsWith("5")?c:0;double ms=t.totalTime(TimeUnit.MILLISECONDS);
            count+=c;errors+=error;total+=ms;var row=endpoints.computeIfAbsent(method+" "+uri,k->new double[3]);row[0]+=c;row[1]+=ms;row[2]+=error;
        }
        out.put("apiCalls",count==0?null:count);out.put("avgLatencyMs",count==0?null:total/count);out.put("http5xx",count==0?null:errors);out.put("tokenUsage",tokenUsage());out.put("businessOutcomes",businessOutcomes());out.put("modelStages",modelStages());out.put("retrievalDocuments",null);
        out.put("hotEndpoints",endpoints.entrySet().stream().sorted((a,b)->Double.compare(b.getValue()[0],a.getValue()[0])).map(e->{String[] key=e.getKey().split(" ",2);double[] v=e.getValue();return Map.of("method",key[0],"uri",key[1],"calls",(long)v[0],"avgLatencyMs",v[0]==0?0:v[1]/v[0],"http5xx",(long)v[2]);}).toList());
        Map<String,Object> memory=new LinkedHashMap<>();memory.put("heapUsedBytes",heap.getUsed());memory.put("heapCommittedBytes",heap.getCommitted());memory.put("heapMaxBytes",heap.getMax()<0?null:heap.getMax());out.put("memory",memory);
        Map<String,Object> rerank=new LinkedHashMap<>();var timer=registry.find("rag.rerank.duration").timer();var fallback=registry.find("rag.rerank.fallback").counter();var timeout=registry.find("rag.rerank.timeout").counter();
        rerank.put("calls",timer==null?null:timer.count());rerank.put("avgLatencyMs",timer==null||timer.count()==0?null:timer.mean(TimeUnit.MILLISECONDS));rerank.put("fallbacks",fallback==null?null:fallback.count());rerank.put("timeouts",timeout==null?null:timeout.count());out.put("rerank",rerank);
        out.put("availability",Map.of("http",count==0?"no_samples":"measured","tokenUsage",registry.find("gen_ai.client.token.usage").counters().isEmpty()?"no_samples":"measured","retrievalDocuments","unavailable"));return out;
    }

    private List<Map<String,Object>> businessOutcomes() {
        List<Map<String,Object>> rows = new ArrayList<>();
        for (String name : List.of("oncall.diagnosis.outcomes", "oncall.chat.outcomes"))
            for (Counter c : registry.find(name).counters())
                rows.add(Map.of("mode", Objects.toString(c.getId().getTag("mode"), "chat"),
                        "status", Objects.toString(c.getId().getTag("status"), "failed"), "count", (long)c.count()));
        return rows;
    }
    private List<Map<String,Object>> modelStages() {
        return registry.find("oncall.model.stage").timers().stream().map(t -> Map.<String,Object>of(
                "stage", Objects.toString(t.getId().getTag("stage"), ""),
                "outcome", Objects.toString(t.getId().getTag("outcome"), ""),
                "count", t.count(), "avgLatencyMs", t.count() == 0 ? 0 : t.mean(TimeUnit.MILLISECONDS))).toList();
    }
    private Map<String,Object> tokenUsage() {
        Map<String,long[]> byModel = new TreeMap<>();
        for (Counter counter : registry.find("gen_ai.client.token.usage").counters()) {
            String operation = counter.getId().getTag("gen_ai.operation.name");
            if (operation != null && !Set.of("chat", "text_completion").contains(operation)) continue;
            String type = counter.getId().getTag("gen_ai.token.type");
            if (!Set.of("input", "output", "total").contains(Objects.toString(type, ""))) continue;
            String model = counter.getId().getTag("gen_ai.request.model");
            if (model == null || model.isBlank()) model = Objects.toString(counter.getId().getTag("model"), "平台未返回模型名");
            long[] values = byModel.computeIfAbsent(model, ignored -> new long[4]);
            if ("input".equals(type)) values[0] += Math.round(counter.count());
            else if ("output".equals(type)) values[1] += Math.round(counter.count());
            else { values[2] += Math.round(counter.count()); values[3] = 1; }
        }
        if (byModel.isEmpty()) return null;
        byModel.values().forEach(v -> { if (v[3] == 0) v[2] = v[0] + v[1]; });
        List<Map<String,Object>> models = byModel.entrySet().stream()
                .sorted((a,b) -> Long.compare(b.getValue()[2], a.getValue()[2]))
                .map(e -> Map.<String,Object>of("model", e.getKey(), "inputTokens", e.getValue()[0],
                        "outputTokens", e.getValue()[1], "totalTokens", e.getValue()[2])).toList();
        return Map.of("inputTokens", byModel.values().stream().mapToLong(v -> v[0]).sum(),
                "outputTokens", byModel.values().stream().mapToLong(v -> v[1]).sum(),
                "totalTokens", byModel.values().stream().mapToLong(v -> v[2]).sum(),
                "models", models, "source", "DashScope usage / Spring AI observation", "window", "进程累计");
    }
}
