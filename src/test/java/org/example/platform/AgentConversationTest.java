package org.example.platform;
import org.junit.jupiter.api.*;
import org.example.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class AgentConversationTest {
    ObjectMapper json=new ObjectMapper();PlatformCatalog catalog;AgentRunStore store;AgentRuntime runtime;
    ChatModelFactory models=mock(ChatModelFactory.class);SessionAttachmentService materials=mock(SessionAttachmentService.class);
    AiOpsService incidents=mock(AiOpsService.class);String agentId;
    @BeforeEach void setup() {
        var data=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__platform_catalog.sql"), new ClassPathResource("db/migration/V5__session_origin.sql")).execute(data);
        catalog=new PlatformCatalog(new JdbcTemplate(data),json);catalog.initializeDefaults();store=new AgentRunStore(new JdbcTemplate(data),catalog);
        var config=new PlatformModels.AgentConfig("任意诊断助手","","任务","你好",List.of("existing-knowledge"),List.of("knowledge-tools"),"workflow",8,120,.1,10,3,2000);
        agentId=catalog.saveAgent(null,config).id();
        var search=mock(KnowledgeSearch.class);when(search.scope(anyList())).thenReturn(new KnowledgeSearch.Scope(List.of(),Set.of(),List.of()));
        var mcp=mock(McpConnections.class);when(mcp.list()).thenReturn(List.of());when(models.modelName()).thenReturn("mock");
        when(models.call(any(),eq("task-routing"),anyString(),anyString())).thenReturn("{\"task\":\"GREETING\"}");
        when(models.call(any(),eq("workflow-supervisor"),anyString(),anyString())).thenReturn("{\"directions\":[\"connection refused\"]}");
        when(models.call(any(),eq("workflow-worker"),anyString(),anyString())).thenReturn("{\"findings\":[],\"actions\":[],\"missingEvidence\":[]}");
        when(models.streamText(any(),eq("direct-response"),anyString(),anyString(),any())).thenReturn("你好，有什么需要？");
        runtime=new AgentRuntime(catalog,store,search,new AgentToolRegistry(catalog,search,mcp,json),models,new AgentAnswerService(json,new DiagnosticReportService()),incidents,json,materials);
    }
    @AfterEach void close(){runtime.close();}
    AgentRunStore.Run await(AgentRunStore.Run run)throws Exception {
        long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while(AgentRunStore.active(run.status())&&System.nanoTime()<until){Thread.sleep(10);run=store.get(run.id());}
        assertThat(AgentRunStore.active(run.status())).isFalse();return run;
    }
    @Test void greetingThenMissingMaterialsClarifiesWithoutMockCapture()throws Exception {
        when(models.call(any(),eq("task-routing"),anyString(),anyString()))
                .thenReturn("{\"task\":\"GREETING\"}","{\"task\":\"INCIDENT_DIAGNOSIS\"}");
        var hello=await(runtime.start("alice",new AgentRuntime.Input(agentId,null,"hello",null,false)));
        assertThat(catalog.decode(hello.resultJson(),AgentRuntime.Result.class).kind()).isEqualTo("chat_reply");
        var missing=await(runtime.start("alice",new AgentRuntime.Input(agentId,hello.sessionId(),"分析故障",null,true)));
        assertThat(catalog.decode(missing.resultJson(),AgentRuntime.Result.class).kind()).isEqualTo("clarification");
        verifyNoInteractions(incidents);verify(models,never()).response(any(),anyString(),any());
    }
    @Test void semanticGreetingAndCasualChatGenerateDifferentDirectRepliesWithoutTools()throws Exception {
        when(models.call(any(),eq("task-routing"),anyString(),anyString()))
                .thenReturn("{\"task\":\"GREETING\"}","{\"task\":\"CASUAL_CHAT\"}");
        when(models.streamText(any(),eq("direct-response"),anyString(),anyString(),any()))
                .thenReturn("早上好，今天想聊什么？","不客气，有需要继续说。");
        var morning=await(runtime.start("alice",new AgentRuntime.Input(agentId,null,"早上好",null,false)));
        var morningResult=catalog.decode(morning.resultJson(),AgentRuntime.Result.class);
        assertThat(morningResult.kind()).isEqualTo("chat_reply");
        assertThat(morningResult.task()).isEqualTo("GREETING");
        assertThat(morningResult.text()).isEqualTo("早上好，今天想聊什么？");
        assertThat(morningResult.toolCalls()).isZero();
        var thanks=await(runtime.start("alice",new AgentRuntime.Input(agentId,morning.sessionId(),"谢谢你",null,false)));
        var thanksResult=catalog.decode(thanks.resultJson(),AgentRuntime.Result.class);
        assertThat(thanksResult.text()).isEqualTo("不客气，有需要继续说。");
        assertThat(thanksResult.task()).isEqualTo("CASUAL_CHAT");
        assertThat(thanksResult.toolCalls()).isZero();
        verify(models,never()).response(any(),eq("answer-synthesis"),any());
    }
    @Test void pastedLogsBecomeFrozenObservedEvidenceAfterGreeting()throws Exception {
        when(models.call(any(),eq("task-routing"),anyString(),anyString()))
                .thenReturn("{\"task\":\"GREETING\"}","{\"task\":\"INCIDENT_DIAGNOSIS\"}");
        var hello=await(runtime.start("alice",new AgentRuntime.Input(agentId,null,"hello",null,false)));
        when(models.response(any(),eq("answer-synthesis"),any())).thenReturn(new org.springframework.ai.chat.model.ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(
            org.springframework.ai.chat.messages.AssistantMessage.builder().content("").toolCalls(List.of(new org.springframework.ai.chat.messages.AssistantMessage.ToolCall("1","function","submit_answer","{\"findings\":[],\"actions\":[],\"missingEvidence\":[\"需要补充文档\"]}"))).build()))));
        when(models.streamText(any(),anyString(),anyString(),anyString(),any())).thenReturn("{\"findings\":[],\"actions\":[],\"missingEvidence\":[\"需要补充文档\"]}");
        var run=await(runtime.start("alice",new AgentRuntime.Input(agentId,hello.sessionId(),"分析日志",null,true,"service=api ERROR connection refused")));
        assertThat(run.status()).isEqualTo("insufficient_evidence");
        var context=catalog.decode(run.contextJson(),AgentRuntime.Context.class);
        assertThat(context.incident().scenarioId()).isEqualTo("user-materials");
        assertThat(context.incident().observations().get("L1")).contains("connection refused");
        var result=catalog.decode(run.resultJson(),AgentRuntime.Result.class);
        assertThat(result.kind()).isEqualTo("incident_report");assertThat(result.evidence()).anySatisfy(e->assertThat(e.content()).contains("connection refused"));
        verifyNoInteractions(incidents);
        assertThat(catalog.decode(store.get(hello.id()).contextJson(),AgentRuntime.Context.class).incident()).isNull();
    }
    @Test void agentWithConversationCannotBeDeletedDirectly()throws Exception {
        var run=await(runtime.start("alice",new AgentRuntime.Input(agentId,null,"hello",null,false)));
        assertThatThrownBy(()->catalog.deleteAgent(agentId))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("先删除");
        assertThat(catalog.agent(agentId,null).enabled()).isTrue();
        assertThat(store.history(run.sessionId(),"alice")).hasSize(1);
        assertThat(store.events(run.id(),0)).anySatisfy(e->assertThat(e.type()).isEqualTo("terminal"));
    }
}
