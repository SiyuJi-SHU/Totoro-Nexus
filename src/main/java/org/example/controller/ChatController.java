package org.example.controller;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.Getter;
import lombok.Setter;
import org.example.service.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import reactor.core.Disposable;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Chat/session transport. Diagnosis jobs are owned by DiagnosisController. */
@RestController
@RequestMapping("/api")
public class ChatController {
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(ChatController.class);
    @Autowired private ChatService chatService;
    @Autowired private RagService ragService;
    @Autowired private ConversationStore conversations;
    @Autowired private DiagnosisController diagnostics;
    @Autowired private MeterRegistry meters;
    private final ExecutorService executor = new ThreadPoolExecutor(4,4,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(16),
            r -> { Thread t = new Thread(r,"chat-request"); t.setDaemon(true); return t; },new ThreadPoolExecutor.AbortPolicy());

    private static void validate(ChatRequest request) {
        if (request == null || request.getQuestion() == null || request.getQuestion().isBlank() || request.getQuestion().length() > 8000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请输入1–8000字的问题");
        if (request.getHistoricalReport() != null && request.getHistoricalReport().length() > 20000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"历史报告超过上下文长度限制");
    }
    private String input(ChatRequest request, String session, AiOpsService.DiagnosisOutcome diagnosis) throws Exception {
        String question = request.getQuestion();
        if (diagnosis == null && request.getHistoricalReport() != null && !request.getHistoricalReport().isBlank())
            question += "\n用户提供的旧页面报告（缺少服务器证据存档，不得当作已核实现场；不能调用当前Mock替代）：\n" + request.getHistoricalReport();
        return chatService.conversationInput(conversations.history(session),question,diagnosis);
    }
    // HTTP compatibility routes use the shared platform engine; these methods remain for legacy unit contracts.
    public ResponseEntity<ApiResponse<ChatResponse>> chat(@RequestBody ChatRequest request) {
        validate(request);
        String id = ConversationStore.sessionId(request.getId());
        try {
            var diagnosis = conversations.diagnosis(id,request.getDiagnosisId());
            var agent = chatService.createReactAgent(chatService.createStandardChatModel(chatService.createDashScopeApi()),
                    chatService.buildSystemPrompt(List.of()),diagnosis == null ? null : diagnosis.snapshot());
            String answer = chatService.executeChat(agent,input(request,id,diagnosis));
            conversations.append(id,request.getQuestion(),answer);
            meters.counter("oncall.chat.outcomes","status","completed").increment();
            ChatResponse response = ChatResponse.success(answer); response.setSessionId(id);
            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (ResponseStatusException e) { throw e; }
        catch (Exception e) {
            meters.counter("oncall.chat.outcomes","status","failed").increment();
            return ResponseEntity.status(503).body(ApiResponse.error("对话未完成，请重试"));
        }
    }
    public SseEmitter chatStream(@RequestBody ChatRequest request) {
        validate(request);
        String id = ConversationStore.sessionId(request.getId());
        SseEmitter emitter = new SseEmitter(180000L);
        AtomicBoolean terminal = new AtomicBoolean();
        AtomicReference<Disposable> subscription = new AtomicReference<>();
        FutureTask<Void> job = new FutureTask<>(() -> {
            try {
                var diagnosis = conversations.diagnosis(id,request.getDiagnosisId());
                var agent = chatService.createReactAgent(chatService.createStandardChatModel(chatService.createDashScopeApi()),
                        chatService.buildSystemPrompt(List.of()),diagnosis == null ? null : diagnosis.snapshot());
                StringBuilder answer = new StringBuilder();
                Disposable active = agent.stream(input(request,id,diagnosis)).subscribe(output -> {
                    if (terminal.get()) return;
                    if (output instanceof StreamingOutput value && value.getOutputType() == OutputType.AGENT_MODEL_STREAMING) {
                        String part = value.message().getText();
                        if (part != null && !part.isEmpty()) {
                            answer.append(part);
                            try { emitter.send(SseEmitter.event().name("message").data(SseMessage.content(part),MediaType.APPLICATION_JSON)); }
                            catch (IOException e) { throw new CancellationException("连接已断开"); }
                        }
                    }
                }, error -> finishError(emitter,terminal,"对话未完成，请重试"), () -> {
                    if (terminal.get()) return;
                    try {
                        conversations.append(id,request.getQuestion(),answer.toString());
                        emitter.send(SseEmitter.event().name("message").data(SseMessage.done(),MediaType.APPLICATION_JSON));
                        terminal.set(true); meters.counter("oncall.chat.outcomes","status","completed").increment(); emitter.complete();
                    } catch (Exception e) { finishError(emitter,terminal,"对话记录保存失败，请重试"); }
                });
                subscription.set(active);
                if (terminal.get()) active.dispose();
            } catch (Exception e) { finishError(emitter,terminal,e instanceof ResponseStatusException r ? r.getReason() : "对话初始化失败，请重试"); }
            return null;
        });
        Runnable cancel = () -> {
            if (terminal.compareAndSet(false,true)) {
                job.cancel(true);
                Disposable active = subscription.get(); if (active != null) active.dispose();
                meters.counter("oncall.chat.outcomes","status","cancelled").increment();
            }
        };
        emitter.onTimeout(cancel); emitter.onError(e -> cancel.run()); emitter.onCompletion(cancel);
        try { executor.execute(job); }
        catch (RejectedExecutionException e) { throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"对话任务繁忙"); }
        return emitter;
    }
    private void finishError(SseEmitter emitter, AtomicBoolean terminal, String message) {
        if (!terminal.compareAndSet(false,true)) return;
        meters.counter("oncall.chat.outcomes","status","failed").increment();
        try { emitter.send(SseEmitter.event().name("message").data(SseMessage.error(message),MediaType.APPLICATION_JSON)); } catch (IOException ignored) {}
        emitter.complete();
    }
    public ResponseEntity<ApiResponse<String>> clearChatHistory(@RequestBody ClearRequest request) throws Exception {
        conversations.clear(ConversationStore.sessionId(request.getId()));
        return ResponseEntity.ok(ApiResponse.success("会话上下文已清空"));
    }
    public ResponseEntity<ApiResponse<SessionInfoResponse>> getSessionInfo(@PathVariable String sessionId) throws Exception {
        String id = ConversationStore.sessionId(sessionId);
        SessionInfoResponse info = new SessionInfoResponse(); info.setSessionId(id); info.setMessagePairCount(conversations.history(id).size()/2);
        var diagnostic = conversations.diagnosis(id,null);
        if (diagnostic != null) info.setCreateTime(java.time.Instant.parse(diagnostic.snapshot().capturedAt()).toEpochMilli());
        return ResponseEntity.ok(ApiResponse.success(info));
    }
    // Java callers of the old controller API keep working; HTTP mappings live in DiagnosisController.
    public SseEmitter aiOps() { return diagnostics.manual(null); }
    public Flux<org.springframework.http.codec.ServerSentEvent<String>> smartDiagnosis(org.example.dto.SmartDiagnosisRequest request) { return diagnostics.smart(request); }
    @PreDestroy public void close() { executor.shutdownNow(); }

    @PostMapping("/chat/rag-eval")
    public ResponseEntity<ApiResponse<RagEvalResponse>> ragEval(@RequestBody ChatRequest request) {
        RagEvalResponse response = new RagEvalResponse();

        if (request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
            response.setSuccess(false);
            response.setErrorMessage("问题内容不能为空");
            return ResponseEntity.ok(ApiResponse.success(response));
        }

        try {
            ragService.queryStream(request.getQuestion(), new RagService.StreamCallback() {
                @Override
                public void onSearchResults(List<VectorSearchService.SearchResult> results) {
                    response.setContexts(results.stream()
                            .map(VectorSearchService.SearchResult::getContent)
                            .toList());
                    response.setSourceFiles(results.stream()
                            .map(VectorSearchService.SearchResult::getSourceFile)
                            .toList());
                    response.setChunkIndexes(results.stream()
                            .map(VectorSearchService.SearchResult::getChunkIndex)
                            .toList());
                    response.setTitles(results.stream()
                            .map(VectorSearchService.SearchResult::getTitle)
                            .toList());
                    response.setScores(results.stream()
                            .map(VectorSearchService.SearchResult::getScore)
                            .toList());
                }

                @Override
                public void onReasoningChunk(String chunk) {
                    // RAGAS 仅评估最终答案，不保存模型的思考过程。
                }

                @Override
                public void onContentChunk(String chunk) {
                    // onComplete 会提供完整答案，避免重复拼接流式增量。
                }

                @Override
                public void onComplete(String fullContent, String fullReasoning) {
                    response.setSuccess(true);
                    response.setAnswer(fullContent);
                }

                @Override
                public void onError(Exception e) {
                    response.setSuccess(false);
                    response.setErrorMessage(e.getMessage());
                }
            });
        } catch (Exception e) {
            logger.error("RAG 评测请求失败", e);
            response.setSuccess(false);
            response.setErrorMessage(e.getMessage());
        }

        return ResponseEntity.ok(ApiResponse.success(response));
    }


    /**
     * 聊天请求
     */
    @Setter
    @Getter
    public static class ChatRequest {
        @com.fasterxml.jackson.annotation.JsonProperty(value = "Id")
        @com.fasterxml.jackson.annotation.JsonAlias({"id", "ID"})
        private String Id;
        
        @com.fasterxml.jackson.annotation.JsonProperty(value = "Question")
        @com.fasterxml.jackson.annotation.JsonAlias({"question", "QUESTION"})
        private String Question;
        private String diagnosisId;
        private String historicalReport;

    }

    /**
     * 清空会话请求
     */
    @Setter
    @Getter
    public static class ClearRequest {
        @com.fasterxml.jackson.annotation.JsonProperty(value = "Id")
        @com.fasterxml.jackson.annotation.JsonAlias({"id", "ID"})
        private String Id;
    }

    // ==================== 内部类 ====================

    /**
     * 会话信息响应
     */
    @Setter
    @Getter
    public static class SessionInfoResponse {
        private String sessionId;
        private int messagePairCount;
        private long createTime;
    }

    /**
     * 统一聊天响应格式
     * 适用于所有普通返回模式的对话接口
     */
    @Setter
    @Getter
    public static class ChatResponse {
        private boolean success;
        private String answer;
        private String errorMessage;
        private String sessionId;

        public static ChatResponse success(String answer) {
            ChatResponse response = new ChatResponse();
            response.setSuccess(true);
            response.setAnswer(answer);
            return response;
        }

        public static ChatResponse error(String errorMessage) {
            ChatResponse response = new ChatResponse();
            response.setSuccess(false);
            response.setErrorMessage(errorMessage);
            return response;
        }
    }

    /**
     * RAGAS 采集响应：答案与生成时实际使用的检索上下文一一对应。
     */
    @Setter
    @Getter
    public static class RagEvalResponse {
        private boolean success;
        private String answer;
        private List<String> contexts = List.of();
        private List<String> sourceFiles = List.of();
        private List<Integer> chunkIndexes = List.of();
        private List<String> titles = List.of();
        private List<Float> scores = List.of();
        private String errorMessage;
    }

    /**
     * 统一 SSE 流式消息格式
     * 适用于所有 SSE 流式返回模式的对话接口
     */
    @Setter
    @Getter
    public static class SseMessage {
        private String type;  // content: 内容块, error: 错误, done: 完成
        private String data;

        public static SseMessage retrieval(RetrievalTrace trace) throws IOException {
            SseMessage message = new SseMessage();
            message.setType("retrieval");
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.node.ObjectNode payload = mapper.valueToTree(trace);
            payload.put("embeddingDimension", org.example.constant.MilvusConstants.VECTOR_DIM);
            message.setData(mapper.writeValueAsString(payload));
            return message;
        }

        public static SseMessage reportStart() {
            SseMessage message = new SseMessage();
            message.setType("report-start");
            message.setData("");
            return message;
        }

        public static SseMessage content(String data) {
            SseMessage message = new SseMessage();
            message.setType("content");
            message.setData(data);
            return message;
        }

        public static SseMessage error(String errorMessage) {
            SseMessage message = new SseMessage();
            message.setType("error");
            message.setData(errorMessage);
            return message;
        }

        public static SseMessage done() {
            SseMessage message = new SseMessage();
            message.setType("done");
            message.setData(null);
            return message;
        }
    }


    @Getter
    @Setter
    public static class ApiResponse<T> {
        private int code;
        private String message;
        private T data;

        public static <T> ApiResponse<T> success(T data) {
            ApiResponse<T> response = new ApiResponse<>();
            response.setCode(200);
            response.setMessage("success");
            response.setData(data);
            return response;
        }

        public static <T> ApiResponse<T> error(String message) {
            ApiResponse<T> response = new ApiResponse<>();
            response.setCode(500);
            response.setMessage(message);
            return response;
        }

    }
}
