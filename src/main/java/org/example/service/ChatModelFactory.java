package org.example.service;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.example.platform.UsageLedger;
import org.springframework.beans.factory.annotation.Autowired;

/** One model configuration and observation path for chat, diagnosis, rewrite and evaluation. */
@Component
public class ChatModelFactory {
    // Console runs allow at most 300 seconds; ModelDeadline enforces the remaining
    // investigation/run budget and cancellation, including time spent in the queue.
    private static final int MAX_RUN_SECONDS = 300;
    private static final java.net.http.HttpClient HTTP=java.net.http.HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8)).build();
    private static final com.fasterxml.jackson.databind.ObjectMapper STREAM_JSON=new com.fasterxml.jackson.databind.ObjectMapper();
    private final String apiKey;
    private final String model;
    private final ObservationRegistry observations;
    private final MeterRegistry meters;
    @Autowired(required=false) private UsageLedger ledger;
    public String modelName() {return model;}

    /** A run owns its selection; never mutate the shared factory or another agent's model. */
    public ChatModelFactory forModel(String selected) {
        selected=org.example.platform.AgentModels.validate(selected);
        if(selected==null||selected.equals(model))return this;
        var scoped=new ChatModelFactory(apiKey,selected,observations,meters);
        scoped.ledger=ledger;
        return scoped;
    }

    public ChatModelFactory(@Value("${spring.ai.dashscope.api-key}") String apiKey,
            @Value("${spring.ai.dashscope.chat.options.model:deepseek-v4-flash}") String model,
            ObservationRegistry observations, MeterRegistry meters) {
        this.apiKey = apiKey; this.model = model; this.observations = observations; this.meters = meters;
    }
    public DashScopeApi api() {
        var transport=new org.springframework.http.client.JdkClientHttpRequestFactory(HTTP);
        transport.setReadTimeout(java.time.Duration.ofSeconds(MAX_RUN_SECONDS));
        // Observe bytes before the SDK merges function-call fragments. Never log their content.
        var web=WebClient.builder().clientConnector(new JdkClientHttpConnector(HTTP)).filter((request,next)->
            Mono.deferContextual(context->next.exchange(request).map(response->{
                CallTiming timing=context.getOrDefault(CallTiming.class,null);
                if(timing==null)return response;
                timing.httpStatus=response.statusCode().value();
                var observed=response.mutate().body(body->body.doOnNext(bytes->{
                    if(bytes.readableByteCount()>0)timing.received(bytes.readableByteCount());
                })).build();
                if(!response.headers().contentType().map(org.springframework.http.MediaType.TEXT_EVENT_STREAM::isCompatibleWith).orElse(false))return observed;
                // The SDK buffers function arguments until TOOL_CALLS or HTTP EOF.
                // DashScope also returns STOP. End on the actual terminal SSE record,
                // preserving it, so the SDK flushes without waiting for the socket to close.
                return observed.mutate().body(ignored->observed.bodyToFlux(String.class).takeUntil(timing::terminalEvent)
                        .map(data->org.springframework.core.io.buffer.DefaultDataBufferFactory.sharedInstance.wrap(
                                ("data: "+data.replace("\n","\ndata: ")+"\n\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)))).build();
            })));
        return DashScopeApi.builder().apiKey(apiKey).restClientBuilder(org.springframework.web.client.RestClient.builder().requestFactory(transport))
                .webClientBuilder(web).build();
    }
    public DashScopeChatModel create(double temperature, int maxTokens, double topP) {
        var options=DashScopeChatOptions.builder().withModel(model).withTemperature(temperature)
                .withMaxToken(maxTokens).withTopP(topP).build();
        // Routing/planning also have bounded output budgets; don't inherit provider thinking defaults.
        options.setEnableThinking(false);
        return DashScopeChatModel.builder().dashScopeApi(api())
                .defaultOptions(options)
                .retryTemplate(org.springframework.retry.support.RetryTemplate.builder().maxAttempts(1).fixedBackoff(1).build())
                .observationRegistry(observations).build();
    }
    public DashScopeChatModel chat() { return create(0.3, 3000, 0.9); }
    public DashScopeChatModel diagnosis() { return create(0.1, 4000, 0.9); }
    public String rewrite(String prompt) { return call(create(0.1, 256, 0.9), "query-rewrite", prompt); }
    public String call(DashScopeChatModel client, String stage, String prompt) {
        return response(client,stage,new Prompt(prompt)).getResult().getOutput().getText();
    }
    public String call(DashScopeChatModel client, String stage, String instructions, String data) {
        return response(client,stage,new Prompt(List.of(new SystemMessage(instructions),new UserMessage(data)))).getResult().getOutput().getText();
    }
    public ChatResponse response(DashScopeChatModel client,String stage,Prompt prompt) {
        checkCancelled();long start=System.nanoTime();ChatResponse result=null;String outcome="success";
        var timing=new CallTiming(stage,model,start);
        boolean answerCall=List.of("react-step","answer-synthesis").contains(stage);
        try {
            result=answerCall?streamResponse(client,prompt,timing):ModelDeadline.call(()->client.call(prompt),30);
            checkCancelled();return result;
        }
        catch(RuntimeException error){
            outcome=Thread.currentThread().isInterrupted()||error instanceof CancellationException?"cancelled":"failed";
            if(outcome.equals("cancelled")||timing.failureKind==null)timing.failureKind=outcome.equals("cancelled")?"cancelled":failureKind(error);
            if(outcome.equals("cancelled"))throw new CancellationException();
            throw error;
        }
        finally {
            var usage=result==null||result.getMetadata().getUsage().getTotalTokens()==0?null:result.getMetadata().getUsage();
            if(result!=null)timing.requestId=result.getMetadata().getId();
            if(ledger!=null)ledger.record(stage,"chat",model,usage==null?null:usage.getPromptTokens(),usage==null?null:usage.getCompletionTokens(),usage==null?null:usage.getTotalTokens(),start,outcome,timing.details());
            meters.timer("oncall.model.stage","stage",stage,"outcome",outcome).record(System.nanoTime()-start,TimeUnit.NANOSECONDS);
        }
    }
    private ChatResponse streamResponse(DashScopeChatModel client,Prompt prompt,CallTiming timing) {
        long soft=ModelDeadline.current(),hard=ModelDeadline.requestDeadline();
        if(hard==Long.MAX_VALUE)soft=hard=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);
        final long phaseDeadline=soft,runDeadline=hard;
        var options=prompt.getOptions() instanceof DashScopeChatOptions supplied?DashScopeChatOptions.fromOptions(supplied):DashScopeChatOptions.builder().build();
        // RC2's fromOptions omits toolChoice. Preserve required/named completion.
        if(prompt.getOptions() instanceof DashScopeChatOptions supplied)options.setToolChoice(supplied.getToolChoice());
        options.setIncrementalOutput(true);options.setStreamOptions(Map.of("include_usage",true));
        // This SDK merges one function call per streamed fragment. ReAct may
        // choose another tool next turn; Workflow direction parallelism is unaffected.
        options.setParallelToolCalls(false);
        var input=new Prompt(prompt.getInstructions(),options);
        var expiry=Flux.interval(Duration.ZERO,Duration.ofMillis(100)).handle((tick,sink)->{
            long now=System.nanoTime(),last=timing.lastByte.get();
            boolean idle=last>0&&now-last>=TimeUnit.SECONDS.toNanos(10);
            boolean noResponse=last==0&&now-timing.start>=TimeUnit.SECONDS.toNanos(30);
            if(idle||noResponse||expired(now,phaseDeadline,runDeadline,last)){
                timing.failureKind=idle?"upstream_idle":noResponse?"upstream_first_response_timeout":"local_deadline";
                sink.error(idle?new ModelDeadline.LimitException("上游模型连续10秒未返回新数据，本次回答未完成"):
                        noResponse?new ModelDeadline.LimitException("等待上游模型首包超过30秒，本次回答未完成"):new ModelDeadline.LimitException());
            }
        });
        var complete=new AtomicReference<ChatResponse>();
        var subscription=new AtomicReference<org.reactivestreams.Subscription>();
        try {
            new MessageAggregator().aggregate(client.stream(input).doOnSubscribe(subscription::set).takeUntilOther(expiry),complete::set)
                    .contextWrite(context->context.put(CallTiming.class,timing)).blockLast();
        }finally{if(subscription.get()!=null)subscription.get().cancel();}
        var response=complete.get();
        if(response==null||response.getResult()==null||!java.util.Set.of("STOP","TOOL_CALLS").contains(java.util.Objects.toString(response.getResult().getMetadata().getFinishReason(),"").toUpperCase(java.util.Locale.ROOT)))
            throw new IllegalStateException("模型流未完整结束");
        return response;
    }
    private static boolean terminalReason(String reason){return reason!=null&&!reason.isBlank()&&!"null".equalsIgnoreCase(reason);}
    // Keep the hard run limit. Only an actively arriving response may cross the
    // investigation cutoff. Detect a stalled response immediately, without waiting
    // for the investigation cutoff; completed fragments are never invented from partial JSON.
    static boolean expired(long now,long soft,long hard,long lastByte) {
        return now>=hard||(lastByte>0&&now-lastByte>=TimeUnit.SECONDS.toNanos(10))||(now>=soft&&lastByte==0);
    }
    private static String failureKind(Throwable error) {
        for(Throwable e=error;e!=null;e=e.getCause()) {
            if(e instanceof ModelDeadline.LimitException)return "local_deadline";
            if(e instanceof org.springframework.web.reactive.function.client.WebClientResponseException
                ||e instanceof org.springframework.web.client.RestClientResponseException)return "upstream_http";
            if(e instanceof java.io.IOException)return "transport";
        }
        return "model_response";
    }
    private static final class CallTiming {
        final String callId=UUID.randomUUID().toString(),purpose,model;
        final long start;final AtomicLong firstByte=new AtomicLong(),lastByte=new AtomicLong(),byteCount=new AtomicLong(),chunks=new AtomicLong();
        final java.util.function.BiConsumer<String,Object> events=UsageLedger.events();
        volatile Integer httpStatus;String failureKind,requestId,finishReason;
        CallTiming(String purpose,String model,long start){this.purpose=purpose;this.model=model;this.start=start;
            events.accept("model_start",Map.of("callId",callId,"purpose",purpose,"model",model,"startedAt",java.time.Instant.now().toString()));}
        void received(int bytes){long now=System.nanoTime();lastByte.set(now);byteCount.addAndGet(bytes);chunks.incrementAndGet();if(firstByte.compareAndSet(0,now))
            events.accept("model_response",Map.of("callId",callId,"firstResponseMs",(now-start)/1_000_000));}
        boolean terminalEvent(String data){
            if("[DONE]".equals(data.strip()))return true;
            try {
                var event=STREAM_JSON.readTree(data);
                for(var choice:event.path("output").path("choices")){
                    String reason=choice.path("finish_reason").asText("");
                    if(terminalReason(reason)){finishReason=reason;requestId=event.path("request_id").asText(null);return true;}
                }
            }catch(com.fasterxml.jackson.core.JsonProcessingException ignored){/* The SDK reports malformed provider data. */}
            return false;
        }
        Map<String,Object> details(){var result=new LinkedHashMap<String,Object>();result.put("callId",callId);
            if(firstByte.get()!=0)result.put("firstResponseMs",(firstByte.get()-start)/1_000_000);
            if(lastByte.get()!=0){result.put("lastResponseMs",(lastByte.get()-start)/1_000_000);result.put("responseBytes",byteCount.get());result.put("responseChunks",chunks.get());}
            if(httpStatus!=null)result.put("httpStatus",httpStatus);
            if(requestId!=null&&!requestId.isBlank())result.put("requestId",requestId);
            if(terminalReason(finishReason))result.put("finishReason",finishReason);
            if(failureKind!=null)result.put("failureKind",failureKind);
            return result;}
    }
    /** Consume provider deltas on the run thread, preserving cancellation and request-scoped usage accounting. */
    public String streamText(DashScopeChatModel client,String stage,String instructions,String data,
                             java.util.function.Consumer<String> delta) {
        checkCancelled();long start=System.nanoTime();String outcome="success";StringBuilder text=new StringBuilder();
        var timing=new CallTiming(stage,model,start);
        org.springframework.ai.chat.metadata.Usage usage=null;
        var options=DashScopeChatOptions.builder().withModel(model).build();
        options.setIncrementalOutput(true);options.setStreamOptions(java.util.Map.of("include_usage",true));
        var prompt=new Prompt(List.of(new SystemMessage(instructions),new UserMessage(data)),options);
        try {
            // Flux.toStream owns a closeable stream; try-with-resources disposes the subscription when interrupted.
            long remaining=Math.min(TimeUnit.SECONDS.toNanos(40),ModelDeadline.remainingNanos());
            if(remaining<=0)throw new ModelDeadline.LimitException();
            var expiry=reactor.core.publisher.Mono.delay(java.time.Duration.ofNanos(remaining))
                .flatMap(ignored->reactor.core.publisher.Mono.<ChatResponse>error(new ModelDeadline.LimitException()));
            try(var responses=client.stream(prompt).takeUntilOther(expiry).contextWrite(c->c.put(CallTiming.class,timing)).toStream()) {
                var chunks=responses.iterator();
                while(chunks.hasNext()) {
                    checkCancelled();var response=chunks.next();
                    if(response.getMetadata()!=null)timing.requestId=response.getMetadata().getId();
                    if(response.getMetadata()!=null&&response.getMetadata().getUsage()!=null&&response.getMetadata().getUsage().getTotalTokens()>0)usage=response.getMetadata().getUsage();
                    if(response.getResult()!=null){String part=response.getResult().getOutput().getText();if(part!=null&&!part.isEmpty()){text.append(part);delta.accept(part);}}
                }
            }
            checkCancelled();return text.toString();
        }catch(RuntimeException error){outcome=Thread.currentThread().isInterrupted()?"cancelled":"failed";timing.failureKind=failureKind(error);throw error;}
        finally {
            if(ledger!=null)ledger.record(stage,"chat",model,usage==null?null:usage.getPromptTokens(),usage==null?null:usage.getCompletionTokens(),usage==null?null:usage.getTotalTokens(),start,outcome,timing.details());
            meters.timer("oncall.model.stage","stage",stage,"outcome",outcome).record(System.nanoTime()-start,TimeUnit.NANOSECONDS);
        }
    }
    private String measured(String stage, Supplier<String> invocation) {
        checkCancelled();
        long start = System.nanoTime();
        String outcome = "success";
        try { String result = invocation.get(); checkCancelled(); return result; }
        catch (RuntimeException ex) { outcome = ex instanceof CancellationException ? "cancelled" : "failed"; throw ex; }
        finally { meters.timer("oncall.model.stage", "stage", stage, "outcome", outcome)
                .record(System.nanoTime() - start, TimeUnit.NANOSECONDS); }
    }
    public static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("诊断已取消");
    }
}
