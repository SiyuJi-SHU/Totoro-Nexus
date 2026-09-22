package org.example.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.example.dto.*;
import org.example.service.*;
import org.springframework.http.*;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns diagnostic jobs and their streams; chat no longer owns diagnosis orchestration. */
@RestController
@RequestMapping("/api")
public class DiagnosisController {
    private final AiOpsService manual;
    private final SmartAiOpsService smart;
    private final ChatModelFactory models;
    private final ConversationStore conversations;
    private final MeterRegistry meters;
    private final ObjectMapper json = new ObjectMapper();
    private final ExecutorService jobs = new ThreadPoolExecutor(4,4,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(8),
            r -> { Thread t = new Thread(r,"diagnosis-request"); t.setDaemon(true); return t; },new ThreadPoolExecutor.AbortPolicy());
    public DiagnosisController(AiOpsService manual, SmartAiOpsService smart, ChatModelFactory models, ConversationStore conversations, MeterRegistry meters) {
        this.manual = manual; this.smart = smart; this.models = models; this.conversations = conversations; this.meters = meters;
    }
    public SseEmitter manual(@RequestBody(required=false) AIOpsRequest request) {
        if (request != null && request.getUserRequest() != null && request.getUserRequest().length() > 4000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"症状描述最多4000字");
        String session = ConversationStore.sessionId(request == null ? null : request.getSessionId());
        IncidentSnapshot snapshot = manual.capture(request == null ? null : request.getScenarioId(), request == null ? "" : request.getUserRequest(), new org.springframework.ai.tool.ToolCallback[0]);
        SseEmitter emitter = new SseEmitter(120000L);
        AtomicBoolean ended = new AtomicBoolean();
        FutureTask<Void> job = new FutureTask<>(() -> {
            String status = "failed";
            try {
                var outcome = manual.diagnose(snapshot, models.diagnosis(), text -> send(emitter, ChatController.SseMessage.content(text + "\n")));
                ChatModelFactory.checkCancelled(); conversations.save(session, outcome);
                send(emitter, evidence(outcome));
                send(emitter, ChatController.SseMessage.reportStart());
                send(emitter, ChatController.SseMessage.content(outcome.report()));
                var context = new ChatController.SseMessage(); context.setType("diagnosis-context");
                context.setData(json.writeValueAsString(Map.of("diagnosisId",snapshot.id(),"sessionId",session,"status",outcome.status(),"message",outcome.message())));
                send(emitter, context);
                send(emitter, ChatController.SseMessage.done());
                status = outcome.status(); ended.set(true); emitter.complete();
            } catch (Exception e) {
                status = Thread.currentThread().isInterrupted() || ended.get() ? "cancelled" : "failed";
                if (!ended.get()) { try { send(emitter, ChatController.SseMessage.error("诊断未完成：" + reason(e))); } catch (Exception ignored) {} }
                ended.set(true); emitter.complete();
            } finally { meters.counter("oncall.diagnosis.outcomes","mode","manual","status",status).increment(); }
            return null;
        });
        Runnable cancel = () -> { if (ended.compareAndSet(false,true)) job.cancel(true); };
        emitter.onTimeout(cancel); emitter.onError(e -> cancel.run()); emitter.onCompletion(cancel);
        try { jobs.execute(job); } catch (RejectedExecutionException e) { throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"诊断任务繁忙，请稍后重试"); }
        return emitter;
    }
    public Flux<ServerSentEvent<String>> smart(@RequestBody SmartDiagnosisRequest request) {
        if (request == null || request.getSymptoms() == null || request.getSymptoms().isBlank() || request.getSymptoms().length() > 4000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请输入1–4000字的症状描述");
        String session = ConversationStore.sessionId(request.getSessionId());
        IncidentSnapshot snapshot = manual.capture(request.getScenarioId(), request.getSymptoms(), new org.springframework.ai.tool.ToolCallback[0]);
        return Flux.<ServerSentEvent<String>>create(sink -> {
            FutureTask<Void> job = new FutureTask<>(() -> {
                String status = "failed";
                try {
                    var outcome = smart.diagnose(snapshot, models.diagnosis(), message -> {
                        if (sink.isCancelled()) throw new CancellationException();
                        sink.next(ServerSentEvent.builder(message).event("progress").build());
                    });
                    ChatModelFactory.checkCancelled();
                    if (sink.isCancelled()) throw new CancellationException();
                    conversations.save(session,outcome);
                    sink.next(ServerSentEvent.builder(json.writeValueAsString(SmartAiOpsService.result(outcome))).event("result").build());
                    status = outcome.status(); sink.complete();
                } catch (Exception e) {
                    status = sink.isCancelled() || Thread.currentThread().isInterrupted() ? "cancelled" : "failed";
                    if (!sink.isCancelled()) sink.next(ServerSentEvent.builder("诊断未完成：" + reason(e)).event("error").build());
                    sink.complete();
                } finally { meters.counter("oncall.diagnosis.outcomes","mode","smart","status",status).increment(); }
                return null;
            });
            sink.onCancel(() -> job.cancel(true));
            try { jobs.execute(job); }
            catch (RejectedExecutionException e) { sink.error(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"诊断任务繁忙")); }
        }).timeout(Duration.ofSeconds(120)).onErrorResume(TimeoutException.class,
                e -> Flux.just(ServerSentEvent.builder("诊断超时，后台任务已取消").event("error").build()));
    }
    public Object diagnosis(@PathVariable String id, @RequestParam String sessionId) throws Exception { return conversations.diagnosis(ConversationStore.sessionId(sessionId),id); }
    private ChatController.SseMessage evidence(AiOpsService.DiagnosisOutcome outcome) throws Exception {
        var message = new ChatController.SseMessage(); message.setType("retrieval");
        message.setData(json.writeValueAsString(Map.of("purpose","grounded-context","resultCount",outcome.documents().size(),"documents",outcome.documents())));
        return message;
    }
    private static void send(SseEmitter emitter, Object data) {
        ChatModelFactory.checkCancelled();
        try { emitter.send(SseEmitter.event().name("message").data(data,MediaType.APPLICATION_JSON)); }
        catch (Exception e) { throw new CancellationException("连接已断开"); }
    }
    private static String reason(Exception error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause())
            if (cause instanceof TimeoutException) return "已超过诊断时间上限，请缩小问题范围后重试";
        if (error instanceof CancellationException) return "任务已取消";
        if (error instanceof IllegalArgumentException) return "请求中的现场或症状无效";
        return "模型调用、证据读取或输出校验失败，请重试";
    }
    @PreDestroy public void close() { jobs.shutdownNow(); }
}
