package org.example.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.service.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentRuntimeTest {
    @Test void rejectedCandidatesDoNotMislabelAnOtherwiseFinishedRunAsPartial(){
        assertThat(AgentRuntime.completionStatus(true,true,false)).isEqualTo("completed");
        assertThat(AgentRuntime.completionStatus(true,false,false)).isEqualTo("partial");
        assertThat(AgentRuntime.completionStatus(true,false,true)).isEqualTo("completed");
        assertThat(AgentRuntime.completionStatus(false,true,false)).isEqualTo("insufficient_evidence");
        assertThat(AgentRuntime.completionStatus(false,false,false)).isEqualTo("partial");
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void currentTimeUsesTheSameConversationProtocolAsOtherTools(boolean selectPro)throws Exception {
        var data=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__platform_catalog.sql"),new ClassPathResource("db/migration/V5__session_origin.sql")).execute(data);
        var json=new ObjectMapper();var catalog=new PlatformCatalog(new JdbcTemplate(data),json);catalog.initializeDefaults();catalog.saveAgent("oncall",new PlatformModels.AgentConfig("Chat","","Answer from sources","",List.of("existing-knowledge"),List.of("knowledge-tools"),"react",8,120,.1,10,3,4000,2,false,selectPro?"deepseek-v4-pro":null));
        var runStore=new AgentRunStore(new JdbcTemplate(data),catalog);
        var proxy=new org.springframework.aop.framework.ProxyFactory(runStore);
        proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(data),new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        var runs=(AgentRunStore)proxy.getProxy();var search=mock(KnowledgeSearch.class);var models=mock(ChatModelFactory.class);var mcp=mock(McpConnections.class);
        var platformModels=selectPro?mock(ChatModelFactory.class):models;
        if(selectPro)when(platformModels.forModel("deepseek-v4-pro")).thenReturn(models);
        when(models.modelName()).thenReturn(selectPro?"deepseek-v4-pro":"default-model");when(models.call(any(),eq("task-routing"),anyString(),anyString())).thenReturn("{\"task\":\"KNOWLEDGE_QUESTION\"}");when(mcp.list()).thenReturn(List.of());
        var scope=new KnowledgeSearch.Scope(List.of("existing-knowledge"),Set.of("existing-data"),List.of());when(search.scope(anyList())).thenReturn(scope);
        when(search.contextWindows(any(),anyList())).thenAnswer(inv->{List<PlatformChunk> hits=inv.getArgument(1);return hits.stream().map(c->new AgentToolRegistry.Evidence(c.id(),c.documentId(),c.version(),c.sourceFile(),c.title(),c.start(),c.end(),c.content(),"document")).toList();});
        var registry=new AgentToolRegistry(catalog,search,mcp,json,Clock.fixed(Instant.parse("2026-09-17T04:34:56Z"),ZoneOffset.UTC));
        var tool=registry.all().stream().filter(t->t.id().equals("system.current_time")).findFirst().orElseThrow();
        var previewContext=new AgentToolRegistry.Context(scope,null,catalog.agent("oncall",null).config(),(type,value)->{});
        @SuppressWarnings("unchecked") var preview=(Map<String,Object>)registry.execute(tool,json.createObjectNode(),previewContext);
        var evidence=(AgentToolRegistry.Evidence)preview.get("evidence");
        String finding="当前日期时间：2026-09-17T12:34:56+08:00";
        String finalJson=catalog.encode(Map.of("answerText",finding,"citations",List.of(Map.of("id",evidence.id(),"quote",evidence.content())),"missingEvidence",List.of()));
        assertThat(EvidenceQuotes.resolve(evidence.content(),finding)).isPresent();
        assertThat(EvidenceAssertions.supported(finding,evidence.content())).isTrue();
        when(models.response(any(),eq("react-step"),any(Prompt.class))).thenReturn(tool("1","current_datetime","{}"),tool("2","submit_answer",finalJson));
        var attachments=mock(SessionAttachmentService.class);when(attachments.list(anyString(),anyString())).thenReturn(List.of());
        var runtime=new AgentRuntime(catalog,runs,search,registry,platformModels,new AgentAnswerService(json,new DiagnosticReportService()),mock(AiOpsService.class),json,attachments);
        try {
            var run=runtime.start("alice",new AgentRuntime.Input("oncall",null,"香港现在几点？",null,false));
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
            while(AgentRunStore.active(run.status())&&System.nanoTime()<deadline){Thread.sleep(20);run=runs.get(run.id());}
            assertThat(run.status()).as("events=%s result=%s",runs.events(run.id(),0),run.resultJson()).isEqualTo("completed");
            assertThat(catalog.decode(run.resultJson(),AgentRuntime.Result.class).text()).contains("2026-09-17T12:34:56+08:00");
            assertThat(catalog.decode(run.resultJson(),AgentRuntime.Result.class).model()).isEqualTo(models.modelName());
            verify(models).call(any(),eq("task-routing"),anyString(),anyString());
            var prompts=org.mockito.ArgumentCaptor.forClass(Prompt.class);
            verify(models,times(2)).response(any(),eq("react-step"),prompts.capture());
            assertThat(prompts.getAllValues()).allSatisfy(p->assertThat(((com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions)p.getOptions()).getModel()).isEqualTo(models.modelName()));
            if(selectPro){verify(platformModels).forModel("deepseek-v4-pro");verifyNoMoreInteractions(platformModels);}
            assertThat(runs.events(run.id(),0).stream().map(AgentRunStore.Event::type)).contains("tool_start","tool_end","validation","terminal");
            assertThat(runs.events(run.id(),0).stream().filter(event->event.type().equals("tool_end")).map(event->catalog.encode(event.data()))).anySatisfy(payload->assertThat(payload).contains("system.current_time","Asia/Hong_Kong"));
            verify(models,never()).streamText(any(),eq("answer-audit"),anyString(),anyString(),any());
        }finally{runtime.close();}
    }

    @Test void nativeToolLoopRecoversFromVectorFailureUsingIndependentTextAndAuditsOnce()throws Exception {
        var data=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__platform_catalog.sql"), new ClassPathResource("db/migration/V5__session_origin.sql")).execute(data);
        var json=new ObjectMapper();var catalog=new PlatformCatalog(new JdbcTemplate(data),json);catalog.initializeDefaults();catalog.saveAgent("oncall",new PlatformModels.AgentConfig("Chat","","Answer from sources","",List.of("existing-knowledge"),List.of("knowledge-tools"),"react",8,120,.1,10,3,4000));
        var runStore=new AgentRunStore(new JdbcTemplate(data),catalog);
        // Match production transaction boundaries: the terminal state and its event commit together.
        var proxy=new org.springframework.aop.framework.ProxyFactory(runStore);
        proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(data),new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        var runs=(AgentRunStore)proxy.getProxy();var search=mock(KnowledgeSearch.class);var models=mock(ChatModelFactory.class);var mcp=mock(McpConnections.class);
        when(models.modelName()).thenReturn("qwen-plus");when(models.call(any(),eq("task-routing"),anyString(),anyString())).thenReturn("{\"task\":\"KNOWLEDGE_QUESTION\"}");when(mcp.list()).thenReturn(List.of());
        when(search.scope(anyList())).thenReturn(new KnowledgeSearch.Scope(List.of("existing-knowledge"),Set.of("existing-data"),List.of()));
        when(search.contextWindows(any(),anyList())).thenAnswer(inv->{List<PlatformChunk> hits=inv.getArgument(1);return hits.stream().map(c->new AgentToolRegistry.Evidence(c.id(),c.documentId(),c.version(),c.sourceFile(),c.title(),c.start(),c.end(),c.content(),"document")).toList();});
        when(search.search(any(),anyString(),anyString(),anyInt(),anyInt(),anyBoolean())).thenThrow(new IllegalStateException("vector offline"));
        var chunk=new PlatformChunk("D-12345678901234567890","doc","version","existing-data","model.md","Model",0,0,28,"DEXINED is an edge detector.",null,5.0,null,"keyword");
        when(search.searchText(any(),eq("DEXINED"),isNull(),anyInt())).thenReturn(List.of(chunk));
        String finalJson="""
        {"answerText":"DEXINED is an edge detector.","citations":[{"id":"D-12345678901234567890","spanIds":["s0"]}],"missingEvidence":[]}
        """;
        when(models.response(any(),eq("react-step"),any(Prompt.class))).thenReturn(tool("1","search_knowledge","{\"query\":\"DEXINED\",\"mode\":\"semantic\"}"),tool("2","search_document_text","{\"query\":\"DEXINED\"}"),tool("3","submit_answer",finalJson));
        when(models.call(any(),eq("answer-grounding"),anyString(),anyString())).thenReturn("{\"decisions\":[{\"id\":\"findings/0\",\"status\":\"supported\",\"text\":\"\",\"reason\":\"direct quote\"}]}");
        var registry=new AgentToolRegistry(catalog,search,mcp,json);
        var sessionAttachmentService = mock(SessionAttachmentService.class);
        when(sessionAttachmentService.list(anyString(), anyString())).thenReturn(List.of());
        var runtime=new AgentRuntime(catalog,runs,search,registry,models,new AgentAnswerService(json,new DiagnosticReportService()),mock(AiOpsService.class),json,sessionAttachmentService);
        try {
            List<AgentRunStore.Run> longHistory=new ArrayList<>();
            for(int i=0;i<8;i++)longHistory.add(new AgentRunStore.Run("history-"+i,"session","oncall",1,"completed",catalog.encode(new AgentRuntime.Input("oncall","session","question",null,i==0)),"{}","{}",null,"now","now"));
            assertThat(runtime.conversationHistory(longHistory).stream().map(AgentRunStore.Run::id)).containsExactly("history-0","history-4","history-5","history-6","history-7");
            var frozen=new KnowledgeSearch.Scope(List.of("existing-knowledge"),Set.of("existing-data"),List.of());
            var run=runtime.start("alice",new AgentRuntime.Input("oncall",null,"DEXINED是什么？",null,false),frozen);
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
            while(AgentRunStore.active(run.status())&&System.nanoTime()<deadline){Thread.sleep(20);run=runs.get(run.id());}
            assertThat(run.status()).isEqualTo("completed");assertThat(run.resultJson()).contains("DEXINED is an edge detector");
            assertThat(runs.events(run.id(),0).stream().map(AgentRunStore.Event::type)).contains("tool_error","tool_end","validation","terminal");
            verify(models,never()).streamText(any(),eq("answer-audit"),anyString(),anyString(),any());
            verify(search,times(1)).searchText(any(),eq("DEXINED"),isNull(),anyInt());
            verify(search,never()).scope(anyList());
            var prompts=org.mockito.ArgumentCaptor.forClass(Prompt.class);
            verify(models,times(3)).response(any(),eq("react-step"),prompts.capture());
            assertThat(prompts.getAllValues()).allSatisfy(p->{var options=(com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions)p.getOptions();assertThat(options.getResponseFormat()).isNull();assertThat(options.getToolCallbacks()).anySatisfy(t->assertThat(t.getToolDefinition().name()).isEqualTo("submit_answer"));});
            // A real follow-up returned correct prose but violated the protocol. Never label this as missing knowledge.
            when(models.response(any(),eq("react-step"),any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("2.5，对应模型为 DexiNed。")))));
            var followup=runtime.start("alice",new AgentRuntime.Input("oncall",run.sessionId(),"只回答数字及模型",null,false));
            while(AgentRunStore.active(followup.status())&&System.nanoTime()<deadline){Thread.sleep(20);followup=runs.get(followup.id());}
            assertThat(followup.status()).isEqualTo("failed");assertThat(followup.error()).contains("结构化", "不是知识库无资料");
            verify(models,never()).streamText(any(),eq("answer-audit"),anyString(),anyString(),any());
        }finally{runtime.close();}
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"看看这个文件","读一下发你的东西"})
    void attachmentRequestReadsTheUploadedFile(String question)throws Exception {
        var data=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__platform_catalog.sql"),
                new ClassPathResource("db/migration/V4__session_attachments.sql"),new ClassPathResource("db/migration/V5__session_origin.sql")).execute(data);
        var json=new ObjectMapper();var catalog=new PlatformCatalog(new JdbcTemplate(data),json);catalog.initializeDefaults();
        catalog.saveAgent("oncall",new PlatformModels.AgentConfig("Chat","","Answer from sources","",List.of("existing-knowledge"),List.of("knowledge-tools"),"react",8,120,.1,10,3,4000));
        var runStore=new AgentRunStore(new JdbcTemplate(data),catalog);
        var proxy=new org.springframework.aop.framework.ProxyFactory(runStore);
        proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(data),new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        var runs=(AgentRunStore)proxy.getProxy();var search=mock(KnowledgeSearch.class);var models=mock(ChatModelFactory.class);var mcp=mock(McpConnections.class);
        when(models.modelName()).thenReturn("qwen-plus");
        when(models.call(any(),eq("task-routing"),anyString(),anyString())).thenAnswer(call->{
            var request=json.readTree((String)call.getArgument(3));
            assertThat(request.path("materials").get(0).path("filename").asText()).isEqualTo("resume.txt");
            assertThat(request.toString()).doesNotContain("ATTACHMENT-7329");
            return question.equals("看看这个文件")?"{\"task\":\"CLARIFICATION_NEEDED\",\"basis\":\"SOURCED\"}":
                    "{\"task\":\"KNOWLEDGE_QUESTION\",\"basis\":\"SOURCED\"}";
        });
        when(mcp.list()).thenReturn(List.of());when(search.scope(anyList())).thenReturn(new KnowledgeSearch.Scope(List.of("existing-knowledge"),Set.of("existing-data"),List.of()));
        when(search.contextWindows(any(),anyList())).thenAnswer(inv->{List<PlatformChunk> hits=inv.getArgument(1);return hits.stream().map(c->new AgentToolRegistry.Evidence(c.id(),c.documentId(),c.version(),c.sourceFile(),c.title(),c.start(),c.end(),c.content(),"document")).toList();});
        String attachmentId="attachment-1",content="候选人项目唯一标记 ATTACHMENT-7329；硕士时间为 2024.09 - 2027.06。";
        var attachment=new SessionAttachment(attachmentId,"ignored","resume.txt","text/plain",content.length(),null,Instant.now(),"alice");
        var attachmentService=mock(SessionAttachmentService.class);when(attachmentService.list(anyString(),eq("alice"))).thenReturn(List.of(attachment));when(attachmentService.readContent(attachmentId,"alice")).thenReturn(content);
        String evidenceId="U-"+KnowledgeFiles.digest(attachmentId+KnowledgeFiles.digest(content)+0+content.length()).substring(0,20);
        String answer=catalog.encode(Map.of("answerText","附件包含唯一标记 ATTACHMENT-7329，硕士时间为2024-2027年。","citations",List.of(Map.of("id",evidenceId,"quote",content)),"missingEvidence",List.of()));
        when(models.response(any(),eq("react-step"),any(Prompt.class))).thenAnswer(call->{
            Prompt prompt=call.getArgument(2);
            return prompt.getInstructions().toString().contains("ATTACHMENT-7329")?tool("1","submit_answer",answer):
                    tool("read","read_material","{\"materialId\":\"attachment-1\"}");
        });
        var registry=new AgentToolRegistry(catalog,search,mcp,json);
        var runtime=new AgentRuntime(catalog,runs,search,registry,models,new AgentAnswerService(json,new DiagnosticReportService()),mock(AiOpsService.class),json,attachmentService);
        try {
            var run=runtime.start("alice",new AgentRuntime.Input("oncall",null,question,null,false));
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
            while(AgentRunStore.active(run.status())&&System.nanoTime()<deadline){Thread.sleep(20);run=runs.get(run.id());}
            var result=catalog.decode(run.resultJson(),AgentRuntime.Result.class);
            assertThat(run.status()).as("events=%s result=%s",runs.events(run.id(),0),run.resultJson()).isEqualTo("completed");
            assertThat(result.text()).contains("ATTACHMENT-7329","2024-2027");
            assertThat(result.task()).isEqualTo("KNOWLEDGE_QUESTION");
            assertThat(runs.events(run.id(),0).stream().filter(event->event.type().equals("tool_start")).map(event->catalog.encode(event.data())))
                    .anySatisfy(payload->assertThat(payload).contains("materials.read",attachmentId));
            verify(search,never()).search(any(),anyString(),anyString(),anyInt(),anyInt(),anyBoolean());
        }finally{runtime.close();}
    }
    private static ChatResponse tool(String id,String name,String arguments){return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall(id,"function",name,arguments))).build())));}
}
