package org.example.service;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.example.platform.UsageLedger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class UpstreamCallTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"stop","tool_calls"})
    void longToolAnswerDrainsEveryFragmentBeforeTheTerminalRecord(String finishReason)throws Exception {
        var json=new ObjectMapper();
        String answer=json.writeValueAsString(Map.of("answerText","附件中的人物行动与来源。".repeat(350),"citations",List.of(),"missingEvidence",List.of()));
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var releaseConnection=new java.util.concurrent.CountDownLatch(1);
        var sent=new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/",exchange->{
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);
            try {
                for(int offset=0;offset<=answer.length();offset+=4){
                    boolean terminal=offset+4>answer.length();
                    var function=new LinkedHashMap<String,Object>();if(offset==0)function.put("name","submit_answer");
                    function.put("arguments",answer.substring(offset,Math.min(offset+4,answer.length())));
                    var tool=new LinkedHashMap<String,Object>();tool.put("index",0);tool.put("function",function);
                    if(offset==0){tool.put("id","tool-long");tool.put("type","function");}
                    var choice=Map.of("finish_reason",terminal?finishReason:"null","message",Map.of("role","assistant","content","","tool_calls",List.of(tool)));
                    var chunk=Map.of("request_id","req-long","output",Map.of("choices",List.of(choice)),"usage",Map.of("input_tokens",3000,"output_tokens",terminal?2000:0,"total_tokens",terminal?5000:3000));
                    exchange.getResponseBody().write(("data: "+json.writeValueAsString(chunk)+"\n\n").getBytes(StandardCharsets.UTF_8));
                    exchange.getResponseBody().flush();sent.incrementAndGet();
                    Thread.sleep(1);
                }
                releaseConnection.await(12,TimeUnit.SECONDS);
            }catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            finally{exchange.close();}
        });server.start();
        try {
            var factory=new ChatModelFactory("test-key","deepseek-v4-flash",ObservationRegistry.NOOP,new SimpleMeterRegistry());
            var api=factory.api().mutate().baseUrl("http://127.0.0.1:"+server.getAddress().getPort()).build();
            var options=DashScopeChatOptions.builder().withModel("deepseek-v4-flash").build();
            options.setInternalToolExecutionEnabled(false);
            var client=DashScopeChatModel.builder().dashScopeApi(api).defaultOptions(options).build();
            try(var deadline=ModelDeadline.bind(System.nanoTime()+TimeUnit.SECONDS.toNanos(10))){
                var result=factory.response(client,"answer-synthesis",new Prompt("根据附件回答"));
                assertThat(sent.get()).isGreaterThan(1000);
                assertThat(result.getResult().getOutput().getToolCalls()).singleElement().satisfies(t->assertThat(t.arguments()).isEqualTo(answer));
                assertThat(result.getMetadata().getUsage().getTotalTokens()).isEqualTo(5000);
            }
        }finally{releaseConnection.countDown();server.stop(0);}
    }

    @Test void silentProviderIsCancelledAndMarkedAsLocalDeadline()throws Exception {
        var factory=new ChatModelFactory("test-key","deepseek-v4-flash",ObservationRegistry.NOOP,new SimpleMeterRegistry());
        var ledger=mock(UsageLedger.class);ReflectionTestUtils.setField(factory,"ledger",ledger);
        var client=mock(DashScopeChatModel.class);var cancelled=new java.util.concurrent.CountDownLatch(1);
        when(client.stream(any(Prompt.class))).thenReturn(reactor.core.publisher.Flux.<org.springframework.ai.chat.model.ChatResponse>never().doOnCancel(cancelled::countDown));
        try(var deadline=ModelDeadline.bind(System.nanoTime()+TimeUnit.SECONDS.toNanos(1))){
            assertThatThrownBy(()->factory.response(client,"react-step",new Prompt("test"))).isInstanceOf(ModelDeadline.LimitException.class);
        }
        assertThat(cancelled.await(1,TimeUnit.SECONDS)).isTrue();
        verify(ledger).record(eq("react-step"),eq("chat"),anyString(),isNull(),isNull(),isNull(),anyLong(),eq("failed"),argThat(t->"local_deadline".equals(t.get("failureKind"))));
    }
    @Test void partialStreamIsNeverAcceptedAsACompletedAnswer() {
        var factory=new ChatModelFactory("test-key","deepseek-v4-flash",ObservationRegistry.NOOP,new SimpleMeterRegistry());
        var client=mock(DashScopeChatModel.class);
        var fragment=new org.springframework.ai.chat.model.ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(new org.springframework.ai.chat.messages.AssistantMessage("unfinished"))));
        when(client.stream(any(Prompt.class))).thenReturn(reactor.core.publisher.Flux.just(fragment));
        assertThatThrownBy(()->factory.response(client,"answer-synthesis",new Prompt("test"))).hasMessageContaining("未完整结束");
    }
    @Test void arrivingResponseCanFinishPastInvestigationCutoffButNotRunDeadline()throws Exception {
        long now=TimeUnit.SECONDS.toNanos(100),soft=now-1,hard=now+TimeUnit.SECONDS.toNanos(20);
        assertThat(ChatModelFactory.expired(now,soft,hard,now-1)).isFalse();
        assertThat(ChatModelFactory.expired(now,soft,hard,0)).isTrue();
        assertThat(ChatModelFactory.expired(now,soft,hard,now-TimeUnit.SECONDS.toNanos(11))).isTrue();
        assertThat(ChatModelFactory.expired(now,now+TimeUnit.SECONDS.toNanos(60),hard,now-TimeUnit.SECONDS.toNanos(11))).isTrue();
        assertThat(ChatModelFactory.expired(now,now+TimeUnit.SECONDS.toNanos(60),hard,now-TimeUnit.SECONDS.toNanos(2))).isFalse();
        assertThat(ChatModelFactory.expired(hard,soft,hard,hard-1)).isTrue();
        try(var request=ModelDeadline.bind(hard);var phase=ModelDeadline.bind(soft)){
            assertThat(ModelDeadline.current()).isEqualTo(soft);
            assertThat(ModelDeadline.requestDeadline()).isEqualTo(hard);
        }
        assertThat(ModelDeadline.requestDeadline()).isEqualTo(Long.MAX_VALUE);
    }

    @Test void aStalledStreamCancelsBeforeTheInvestigationBudgetAndRetainsItsFailureKind()throws Exception {
        var factory=new ChatModelFactory("test-key","deepseek-v4-flash",ObservationRegistry.NOOP,new SimpleMeterRegistry());
        var ledger=mock(UsageLedger.class);ReflectionTestUtils.setField(factory,"ledger",ledger);
        var client=mock(DashScopeChatModel.class);var cancelled=new java.util.concurrent.CountDownLatch(1);
        when(client.stream(any(Prompt.class))).thenReturn(reactor.core.publisher.Flux.deferContextual(context->{
            Object timing=context.stream().filter(entry->entry.getKey() instanceof Class<?> c&&c.getSimpleName().equals("CallTiming")).findFirst().orElseThrow().getValue();
            var last=(java.util.concurrent.atomic.AtomicLong)ReflectionTestUtils.getField(timing,"lastByte");
            last.set(System.nanoTime()-TimeUnit.SECONDS.toNanos(11));
            return reactor.core.publisher.Flux.<org.springframework.ai.chat.model.ChatResponse>never().doOnCancel(cancelled::countDown);
        }));
        long start=System.nanoTime();
        try(var deadline=ModelDeadline.bind(start+TimeUnit.SECONDS.toNanos(60))){
            assertThatThrownBy(()->factory.response(client,"react-step",new Prompt("test"))).isInstanceOf(ModelDeadline.LimitException.class).hasMessageContaining("上游模型连续10秒未返回新数据");
        }
        assertThat(System.nanoTime()-start).isLessThan(TimeUnit.SECONDS.toNanos(3));
        assertThat(cancelled.await(1,TimeUnit.SECONDS)).isTrue();
        verify(ledger).record(eq("react-step"),eq("chat"),anyString(),isNull(),isNull(),isNull(),anyLong(),eq("failed"),argThat(t->"upstream_idle".equals(t.get("failureKind"))));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"stop","tool_calls"})
    void sdkAssemblesToolArgumentsAndPreservesUsageAndWireOptions(String finishReason)throws Exception {
        var json=new ObjectMapper();var wire=new AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var releaseConnection=new java.util.concurrent.CountDownLatch(1);
        String answer="{\"answerText\":\"智慧与归乡意志\",\"citations\":[],\"missingEvidence\":[]}";
        server.createContext("/",exchange->{
            wire.set(json.readTree(exchange.getRequestBody()));
            exchange.getResponseHeaders().add("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);
            for(int i=0;i<3;i++){
                var function=new LinkedHashMap<String,Object>();if(i==0)function.put("name","submit_answer");
                function.put("arguments",i==0?answer.substring(0,12):i==1?answer.substring(12):"");
                var tool=new LinkedHashMap<String,Object>();tool.put("index",0);tool.put("function",function);
                if(i==0){tool.put("id","tool-1");tool.put("type","function");}
                var choice=Map.of("finish_reason",i==2?finishReason:"null","message",Map.of("role","assistant","content","","tool_calls",List.of(tool)));
                var chunk=Map.of("request_id","req-test","output",Map.of("choices",List.of(choice)),"usage",Map.of("input_tokens",30,"output_tokens",i==2?20:0,"total_tokens",i==2?50:30));
                exchange.getResponseBody().write(("data: "+json.writeValueAsString(chunk)+"\n\n").getBytes(StandardCharsets.UTF_8));exchange.getResponseBody().flush();
            }
            // A completed model response must not wait for HTTP EOF.
            try{releaseConnection.await(8,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            finally{exchange.close();}
        });server.start();
        try {
            var factory=new ChatModelFactory("test-key","deepseek-v4-flash",ObservationRegistry.NOOP,new SimpleMeterRegistry());
            var ledger=mock(UsageLedger.class);ReflectionTestUtils.setField(factory,"ledger",ledger);
            var api=factory.api().mutate().baseUrl("http://127.0.0.1:"+server.getAddress().getPort()).build();
            var client=DashScopeChatModel.builder().dashScopeApi(api).defaultOptions(DashScopeChatOptions.builder().withModel("deepseek-v4-flash").build()).build();
            var options=DashScopeChatOptions.builder().withMaxToken(4000).withTemperature(.5).build();
            options.setEnableThinking(false);options.setInternalToolExecutionEnabled(false);options.setToolChoice("required");
            try(var deadline=ModelDeadline.bind(System.nanoTime()+TimeUnit.SECONDS.toNanos(5))){
                var result=factory.response(client,"react-step",new Prompt(List.of(new UserMessage("评价奥德修斯")),options));
                assertThat(result.getResult().getOutput().getToolCalls()).singleElement().satisfies(t->{assertThat(t.name()).isEqualTo("submit_answer");assertThat(t.arguments()).isEqualTo(answer);});
                assertThat(result.getMetadata().getUsage().getTotalTokens()).isEqualTo(50);
            }
            var params=wire.get().path("parameters");
            assertThat(params.path("incremental_output").asBoolean()).isTrue();
            assertThat(params.path("enable_thinking").asBoolean(true)).isFalse();
            assertThat(params.path("parallel_tool_calls").asBoolean(true)).isFalse();
            assertThat(params.path("max_tokens").asInt()).isEqualTo(4000);
            assertThat(params.path("tool_choice").asText()).isEqualTo("required");
            verify(ledger).record(eq("react-step"),eq("chat"),eq("deepseek-v4-flash"),eq(30),eq(20),eq(50),anyLong(),eq("success"),argThat(t->t.containsKey("firstResponseMs")&&t.get("httpStatus").equals(200)));
        }finally{releaseConnection.countDown();server.stop(0);}
    }
}
