package org.example.platform;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class DashScopeWireTest {
    @Test void nativeToolRequestPreservesExplicitThinkingAndGenerationOptions()throws Exception {
        try(var f=new GeneralKnowledgeRuntimeTest.Fixture(false,"react","SOURCED")) {
            var tool=f.registry.all().stream().filter(t->t.id().equals("knowledge.list")).findFirst().orElseThrow();
            var context=new AgentToolRegistry.Context(f.scope,null,f.catalog.agent("oncall",null).config(),(t,d)->{});
            context.answerPolicy=AnswerPolicy.CONVERSATIONAL;
            var messages=new ArrayList<Message>(List.of(new SystemMessage("根据工具结果完整回答。"),new UserMessage("列出授权文档")));
            var execution=new AgentExecution("test","列出授权文档",context.config,f.models,f.json,f.registry,context,List.of(tool),messages,new ArrayList<>(),()->{},(t,a,c)->Map.of(),false,System.nanoTime());
            when(f.models.response(any(),anyString(),any())).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("test")))));
            execution.generate(true);
            var prompt=org.mockito.ArgumentCaptor.forClass(Prompt.class);verify(f.models).response(any(),anyString(),prompt.capture());
            var wire=new AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",exchange->{
                wire.set(f.json.readTree(exchange.getRequestBody()));
                byte[] body="{\"request_id\":\"test-request\",\"output\":{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]},\"usage\":{\"input_tokens\":1,\"output_tokens\":1,\"total_tokens\":2}}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
            });server.start();
            try {
                var client=DashScopeChatModel.builder().dashScopeApi(DashScopeApi.builder().apiKey("test-key").baseUrl("http://127.0.0.1:"+server.getAddress().getPort()).build())
                    .defaultOptions(DashScopeChatOptions.builder().withModel("test-model").withTemperature(.1).withMaxToken(4000).withTopP(.9).build()).build();
                client.call(prompt.getValue());
                var params=wire.get().path("parameters");
                assertThat(params.path("enable_thinking").asBoolean(true)).isFalse();
                assertThat(params.path("max_tokens").asInt()).isEqualTo(4000);
                assertThat(params.path("tool_choice").asText()).isEqualTo("required");
                assertThat(params.path("tools")).hasSize(2);
            }finally{server.stop(0);}
        }
    }
}
