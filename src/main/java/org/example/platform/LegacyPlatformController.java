package org.example.platform;

import org.example.controller.ChatController;
import org.example.dto.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.http.*;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Preserve legacy HTTP/SSE shapes while routing every run through the same owned, versioned engine. */
@RestController
@RequestMapping("/api")
public class LegacyPlatformController {
    private final AgentRuntime runtime;private final AgentRunStore store;private final PlatformIdentity identity;private final PlatformCatalog catalog;private final JdbcTemplate db;
    private final ScheduledExecutorService polling=Executors.newScheduledThreadPool(2);
    public LegacyPlatformController(AgentRuntime runtime,AgentRunStore store,PlatformIdentity identity,PlatformCatalog catalog,JdbcTemplate db){this.runtime=runtime;this.store=store;this.identity=identity;this.catalog=catalog;this.db=db;}
    private synchronized String resolveSession(String owner,String alias,String question) {
        if(alias==null||alias.isBlank())return null;alias=PlatformCatalog.required(alias,100,"会话ID");
        var found=db.queryForList("SELECT session_id FROM legacy_session_aliases WHERE owner_id=? AND alias=?",String.class,owner,alias);if(!found.isEmpty())return found.get(0);
        var session=store.openSession(owner,"oncall",null,question);db.update("INSERT INTO legacy_session_aliases(owner_id,alias,session_id) VALUES(?,?,?)",owner,alias,session.id());return session.id();
    }
    private AgentRunStore.Run start(String owner,String alias,String question,String scenario,boolean diagnose){return runtime.start(owner,new AgentRuntime.Input("oncall",resolveSession(owner,alias,question),question,scenario,diagnose));}
    @PostMapping("/chat") public Object chat(Authentication user,@RequestBody ChatController.ChatRequest input)throws Exception {
        String owner=identity.userId(user);var run=start(owner,input.getId(),input.getQuestion(),null,false);
        try {while(AgentRunStore.active(run.status())){Thread.sleep(200);run=store.get(run.id());}}
        catch(InterruptedException error){runtime.cancel(run.id(),owner);Thread.currentThread().interrupt();throw error;}
        if(run.resultJson()==null)return ResponseEntity.status(503).body(Map.of("success",false,"message",Objects.toString(run.error(),"对话失败")));
        var result=catalog.decode(run.resultJson(),AgentRuntime.Result.class);return ChatController.ApiResponse.success(ChatController.ChatResponse.success(result.text()));
    }
    @PostMapping(value="/chat_stream",produces=MediaType.TEXT_EVENT_STREAM_VALUE) public SseEmitter chatStream(Authentication user,@RequestBody ChatController.ChatRequest input){String owner=identity.userId(user);return bridge(start(owner,input.getId(),input.getQuestion(),null,false),owner,false);}
    @PostMapping(value="/ai_ops",produces=MediaType.TEXT_EVENT_STREAM_VALUE) public SseEmitter diagnose(Authentication user,@RequestBody AIOpsRequest input){String owner=identity.userId(user);return bridge(start(owner,input.getSessionId(),input.getUserRequest()==null||input.getUserRequest().isBlank()?"分析所选故障现场":input.getUserRequest(),input.getScenarioId(),true),owner,false);}
    @PostMapping(value="/ai_ops/smart",produces=MediaType.TEXT_EVENT_STREAM_VALUE) public SseEmitter smart(Authentication user,@RequestBody SmartDiagnosisRequest input){String owner=identity.userId(user);return bridge(start(owner,input.getSessionId(),input.getSymptoms(),input.getScenarioId(),true),owner,true);}
    @GetMapping("/diagnoses/{id}") public Object diagnosis(Authentication user,@PathVariable String id){var run=store.owned(id,identity.userId(user));return run.resultJson()==null?Map.of("status",run.status()):catalog.decode(run.resultJson(),Object.class);}
    @GetMapping("/chat/session/{sessionId}") public Object session(Authentication user,@PathVariable String sessionId){String owner=identity.userId(user);var values=db.queryForList("SELECT session_id FROM legacy_session_aliases WHERE owner_id=? AND alias=?",String.class,owner,sessionId);if(values.isEmpty())throw PlatformCatalog.missing("会话");return ChatController.ApiResponse.success(Map.of("sessionId",sessionId,"messagePairCount",store.history(values.get(0),owner).size()));}
    @PostMapping("/chat/clear") public Object clear(Authentication user,@RequestBody Map<String,String> body){db.update("DELETE FROM legacy_session_aliases WHERE owner_id=? AND alias=?",identity.userId(user),body.get("id"));return ChatController.ApiResponse.success("已开启新的会话上下文，旧记录仍可追溯");}
    private SseEmitter bridge(AgentRunStore.Run initial,String owner,boolean smart) {
        SseEmitter emitter=new SseEmitter(330000L);AtomicBoolean ended=new AtomicBoolean();AtomicInteger after=new AtomicInteger();AtomicReference<ScheduledFuture<?>> task=new AtomicReference<>();
        Runnable disconnect=()->{if(ended.compareAndSet(false,true))runtime.cancel(initial.id(),owner);var timer=task.get();if(timer!=null)timer.cancel(false);};
        emitter.onTimeout(disconnect);emitter.onError(e->disconnect.run());emitter.onCompletion(disconnect);
        task.set(polling.scheduleWithFixedDelay(()->{
            try {
                for(var event:store.events(initial.id(),after.get())){after.set(event.sequence());if(event.type().equals("stage"))emitter.send(SseEmitter.event().name(smart?"progress":"message").data(smart?catalog.encode(event.data()):Map.of("type","progress","data",catalog.encode(event.data())),MediaType.APPLICATION_JSON));}
                var run=store.get(initial.id());if(AgentRunStore.active(run.status()))return;
                ended.set(true);
                if(run.resultJson()==null)emitter.send(SseEmitter.event().name(smart?"error":"message").data(smart?Objects.toString(run.error(),"执行失败"):Map.of("type","error","content",Objects.toString(run.error(),"执行失败")),MediaType.APPLICATION_JSON));
                else {
                    var result=catalog.decode(run.resultJson(),AgentRuntime.Result.class);
                    if(smart)emitter.send(SseEmitter.event().name("result").data(Map.of("status",result.status(),"primaryReport",result.text(),"report",result.text(),"diagnosisId",run.id(),"sessionId",run.sessionId(),"evidence",result.evidence()),MediaType.APPLICATION_JSON));
                    else {emitter.send(SseEmitter.event().name("message").data(Map.of("type","report-start"),MediaType.APPLICATION_JSON));emitter.send(SseEmitter.event().name("message").data(ChatController.SseMessage.content(result.text()),MediaType.APPLICATION_JSON));emitter.send(SseEmitter.event().name("message").data(ChatController.SseMessage.done(),MediaType.APPLICATION_JSON));}
                }
                var timer=task.get();if(timer!=null)timer.cancel(false);emitter.complete();
            }catch(Exception error){disconnect.run();emitter.complete();}
        },0,300,TimeUnit.MILLISECONDS));return emitter;
    }
    @PreDestroy public void close(){polling.shutdownNow();}
}
