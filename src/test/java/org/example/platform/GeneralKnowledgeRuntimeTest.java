package org.example.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.util.*;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GeneralKnowledgeRuntimeTest {
    static final String GENERAL="Agent 应用需要明确任务边界，并结合工具反馈判断是否完成。";
    static class Fixture implements AutoCloseable {
        final ObjectMapper json=new ObjectMapper();
        final ChatModelFactory models=mock(ChatModelFactory.class);
        final KnowledgeSearch search=mock(KnowledgeSearch.class);
        final SessionAttachmentService attachments=mock(SessionAttachmentService.class);
        final PlatformCatalog catalog;
        final AgentRunStore runs;
        final AgentToolRegistry registry;
        final AgentRuntime runtime;
        final KnowledgeSearch.Scope scope=new KnowledgeSearch.Scope(List.of("existing-knowledge"),Set.of("existing-data"),List.of());
        Fixture(boolean allowed,String strategy,String basis)throws Exception {
            var data=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");
            new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__platform_catalog.sql"),new ClassPathResource("db/migration/V5__session_origin.sql")).execute(data);
            catalog=new PlatformCatalog(new JdbcTemplate(data),json);catalog.initializeDefaults();
            catalog.saveAgent("oncall",new PlatformModels.AgentConfig("Chat","","按配置回答，内部事实必须查证","",List.of("existing-knowledge"),List.of("knowledge-tools"),strategy,8,120,.1,10,3,4000,2,allowed));
            var proxy=new org.springframework.aop.framework.ProxyFactory(new AgentRunStore(new JdbcTemplate(data),catalog));
            proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(new org.springframework.jdbc.datasource.DataSourceTransactionManager(data),new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
            runs=(AgentRunStore)proxy.getProxy();
            when(models.modelName()).thenReturn("test-model");
            when(models.call(any(),eq("task-routing"),anyString(),anyString())).thenReturn("{\"task\":\"KNOWLEDGE_QUESTION\",\"basis\":\""+basis+"\"}");
            when(search.scope(anyList())).thenReturn(scope);
        when(search.contextWindows(any(),anyList())).thenAnswer(inv->{List<PlatformChunk> hits=inv.getArgument(1);return hits.stream().map(c->new AgentToolRegistry.Evidence(c.id(),c.documentId(),c.version(),c.sourceFile(),c.title(),c.start(),c.end(),c.content(),"document")).toList();});
            when(search.search(any(),anyString(),anyString(),anyInt(),anyInt(),anyBoolean())).thenReturn(new KnowledgeSearch.SearchResult("q","hybrid","no_results",false,List.of(),0,List.of(),List.of(),0,0,0,1));
            var mcp=mock(McpConnections.class);when(mcp.list()).thenReturn(List.of());
            registry=new AgentToolRegistry(catalog,search,mcp,json);
            when(attachments.list(anyString(),anyString())).thenReturn(List.of());
            runtime=new AgentRuntime(catalog,runs,search,registry,models,new AgentAnswerService(json,new DiagnosticReportService()),mock(AiOpsService.class),json,attachments);
        }
        String draft(String text){return catalog.encode(Map.of("answerText",text,"citations",List.of(),"missingEvidence",List.of()));}
        AgentRunStore.Run run(String question)throws Exception {
            var run=runtime.start("alice",new AgentRuntime.Input("oncall",null,question,null,false));
            long until=System.nanoTime()+8_000_000_000L;
            while(AgentRunStore.active(run.status())&&System.nanoTime()<until){Thread.sleep(15);run=runs.get(run.id());}
            assertThat(AgentRunStore.active(run.status())).as("run terminated: %s",run.error()).isFalse();return run;
        }
        public void close(){runtime.close();}
    }
    static ChatResponse submit(String text){return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall("a","function","submit_answer",text))).build())));}

    @Test void deterministicCitationValidationCompletesWithoutASecondModelReview()throws Exception {
        try(var f=new Fixture(false,"react","SOURCED")) {
            String fact="The release process requires testing.",id="D-12345678901234567890";
            var chunk=new PlatformChunk(id,"doc","v","existing-data","process.md","Process",0,0,fact.length(),fact,null,1.0,null,"keyword");
            when(f.search.searchText(any(),anyString(),isNull(),anyInt())).thenReturn(List.of(chunk));
            String draft=f.catalog.encode(Map.of("answerText",fact,"citations",List.of(Map.of("id",id,"quote",fact)),"missingEvidence",List.of()));
            var call=new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall("s","function","search_document_text","{\"query\":\"release\"}"))).build())));
            when(f.models.response(any(),eq("react-step"),any())).thenReturn(call,submit(draft));
            var run=f.run("What is required for release?");
            assertThat(run.status()).isEqualTo("completed");
            assertThat(run.resultJson()).doesNotContain("语义核对");
            assertThat(f.runs.events(run.id(),0).stream().map(AgentRunStore.Event::type)).doesNotContain("semantic_review");
            verify(f.models,never()).call(any(),eq("answer-grounding"),anyString(),anyString());
        }
    }

    @Test void openQuestionSearchesBeforeAnsweringAndDoesNotRequireEvidenceForGeneralKnowledge()throws Exception {
        try(var f=new Fixture(true,"react","GENERAL")){
            when(f.models.response(any(),eq("answer-synthesis"),any(Prompt.class))).thenReturn(submit(f.draft(GENERAL)));
            var run=f.run("探讨一下agent的前景");var result=f.catalog.decode(run.resultJson(),AgentRuntime.Result.class);
            assertThat(run.status()).isEqualTo("completed");assertThat(result.answer().answerText()).isEqualTo(GENERAL);
            assertThat(result.toolCalls()).isEqualTo(1);assertThat(result.text()).doesNotContain("证据不足");
            var order=inOrder(f.search,f.models);order.verify(f.search).search(any(),eq("探讨一下agent的前景"),eq("semantic"),anyInt(),anyInt(),eq(true));order.verify(f.models).response(any(),eq("answer-synthesis"),any());
            verify(f.models,never()).streamText(any(),eq("answer-audit"),anyString(),anyString(),any());
            assertThat(f.runs.events(run.id(),0)).anySatisfy(e->assertThat(e.type()).isEqualTo("answer_text"));
        }
    }
    @Test void sourcedRequestsRetainTheirGroundingInstructionsWithoutUsingTheOldGeneralBypass()throws Exception {
        for(var condition:List.of(Map.entry(false,"GENERAL"),Map.entry(true,"SOURCED"))){
            try(var f=new Fixture(condition.getKey(),"react",condition.getValue())){
                String obsolete=f.catalog.encode(Map.of("findings",List.of(),"actions",List.of(),"missingEvidence",List.of(),"generalExplanation","我们公司生产环境有999台服务器。"));
                when(f.models.response(any(),eq("react-step"),any(Prompt.class))).thenReturn(submit(obsolete));
                var run=f.run("我们公司的生产配置是什么？");
                assertThat(run.status()).isEqualTo("failed");assertThat(run.error()).contains("结构化").doesNotContain("999");assertThat(run.resultJson()).isNull();
                var prompts=org.mockito.ArgumentCaptor.forClass(Prompt.class);verify(f.models).response(any(),eq("react-step"),prompts.capture());
                var options=(com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions)prompts.getValue().getOptions();
                var schema=options.getToolCallbacks().stream().filter(t->t.getToolDefinition().name().equals("submit_answer")).findFirst().orElseThrow().getToolDefinition().inputSchema();
                assertThat(schema).doesNotContain("generalExplanation");
                assertThat(schema).contains("answerText");
                assertThat(prompts.getValue().getInstructions().toString()).contains("本轮不允许通识补充");
            }
        }
    }
    @Test void lookupOutageIsVisibleAndDoesNotBlockGeneralAnswer()throws Exception {
        try(var f=new Fixture(true,"react","GENERAL")){
            when(f.search.search(any(),anyString(),anyString(),anyInt(),anyInt(),anyBoolean())).thenThrow(new IllegalStateException("search unavailable"));
            when(f.models.response(any(),eq("answer-synthesis"),any(Prompt.class))).thenReturn(submit(f.draft(GENERAL)));
            var run=f.run("解释Agent的原理");assertThat(run.status()).isEqualTo("completed");
            assertThat(run.resultJson()).contains("本次检索存在失败或降级");
            assertThat(f.runs.events(run.id(),0)).anySatisfy(e->assertThat(e.type()).isEqualTo("tool_error"));
        }
    }
    @Test void mixedAnswerKeepsSourceLinksAndGeneralAnalysisInTheCompleteBody()throws Exception {
        try(var f=new Fixture(true,"react","MIXED")){
            String id="D-12345678901234567890",fact="发布前必须完成测试。";
            var chunk=new PlatformChunk(id,"doc","v","existing-data","release.md","Release",0,0,fact.length(),fact,null,1.0,null,"keyword");
            when(f.search.searchText(any(),anyString(),isNull(),anyInt())).thenReturn(List.of(chunk));
            var call=new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall("s","function","search_document_text","{\"query\":\"发布流程\"}"))).build())));
            String draft=f.catalog.encode(Map.of("answerText",fact+"\n一般而言，测试有助于尽早发现问题。","citations",List.of(Map.of("id",id,"quote",fact)),"missingEvidence",List.of()));
            when(f.models.response(any(),eq("react-step"),any())).thenReturn(call,submit(draft));
            var run=f.run("解释公司发布流程，并分析测试的价值");var result=f.catalog.decode(run.resultJson(),AgentRuntime.Result.class);
            assertThat(run.status()).isEqualTo("completed");assertThat(result.answer().citations()).hasSize(1);
            assertThat(result.answer().answerText()).contains("尽早发现问题");assertThat(result.text()).contains(fact,id);
            verify(f.models,never()).streamText(any(),eq("answer-audit"),anyString(),anyString(),any());
        }
    }
    @Test void planCanFinishAfterInitialLookupWithoutManufacturingSteps()throws Exception {
        try(var f=new Fixture(true,"plan_execute_replan","GENERAL")){
            when(f.models.call(any(),eq("plan-create"),anyString(),anyString())).thenReturn("{\"action\":\"finish\",\"reason\":\"本次资料无关，可用通识解释\"}");
            when(f.models.response(any(),eq("answer-synthesis"),any())).thenReturn(submit(f.draft(GENERAL)));
            var run=f.run("怎么看AI应用开发");var result=f.catalog.decode(run.resultJson(),AgentRuntime.Result.class);
            assertThat(run.status()).isEqualTo("completed");assertThat(result.toolCalls()).isEqualTo(1);assertThat(result.plan()).isEmpty();
            verify(f.models,never()).call(any(),eq("plan-execute"),anyString(),anyString());
        }
    }
    @Test void directConversationEmitsProviderDeltasBeforeTerminalEvent()throws Exception {
        try(var f=new Fixture(false,"react","SOURCED")){
            when(f.models.call(any(),eq("task-routing"),anyString(),anyString())).thenReturn("{\"task\":\"GREETING\"}");
            when(f.models.streamText(any(),eq("direct-response"),anyString(),anyString(),any())).thenAnswer(call->{Consumer<String> emit=call.getArgument(4);emit.accept("你好");emit.accept("，请说。");return "你好，请说。";});
            var run=f.run("你好");var types=f.runs.events(run.id(),0).stream().map(AgentRunStore.Event::type).toList();
            assertThat(types).containsSubsequence("answer_delta","answer_delta","terminal");
            assertThat(f.catalog.decode(run.resultJson(),AgentRuntime.Result.class).toolCalls()).isZero();
        }
    }
    @Test void oldConfigurationDefaultsOffAndWorkflowCannotEnableGeneralKnowledge()throws Exception {
        try(var f=new Fixture(true,"react","GENERAL")){
            var config=f.catalog.agent("oncall",null).config();assertThat(config.allowGeneralKnowledge()).isTrue();
            var node=f.json.valueToTree(config);((com.fasterxml.jackson.databind.node.ObjectNode)node).remove("allowGeneralKnowledge");
            assertThat(f.json.treeToValue(node,PlatformModels.AgentConfig.class).allowGeneralKnowledge()).isFalse();
            ((com.fasterxml.jackson.databind.node.ObjectNode)node).put("strategy","workflow").put("allowGeneralKnowledge",true);
            assertThat(f.catalog.validateConfig(f.json.treeToValue(node,PlatformModels.AgentConfig.class)).allowGeneralKnowledge()).isFalse();
        }
    }
    @Test void openLookupLimitDoesNotPreventReadingMatchedSmallDocument()throws Exception {
        try(var f=new Fixture(true,"react","GENERAL")){
            var context=new AgentToolRegistry.Context(f.scope,null,f.catalog.agent("oncall",null).config(),(t,d)->{});context.openQuestion=true;
            var search=f.registry.all().stream().filter(t->t.id().equals("knowledge.search")).findFirst().orElseThrow();
            for(int i=0;i<4;i++)f.registry.execute(search,f.json.valueToTree(Map.of("query","q"+i)),context);
            verify(f.search,times(3)).search(any(),anyString(),eq("semantic"),anyInt(),anyInt(),eq(true));
            String content="x".repeat(5000);
            when(f.search.read(any(),eq("doc"),eq("v"),isNull(),eq(0),eq(6000))).thenReturn(new DocumentReadingService.Window("doc","v","doc.md",0,5000,5000,content,false,null));
            var read=f.registry.all().stream().filter(t->t.id().equals("documents.read")).findFirst().orElseThrow();
            var result=f.json.valueToTree(f.registry.execute(read,f.json.valueToTree(Map.of("documentId","doc","version","v")),context));
            assertThat(result.path("truncated").asBoolean()).isFalse();assertThat(result.path("evidence").path("content").asText()).hasSize(5000);
        }
    }
    @Test void knowledgeSearchUsesTheKnowledgeBaseModeUnlessTheAgentExplicitlyOverridesIt()throws Exception {
        try(var f=new Fixture(false,"react","SOURCED")){
            var context=new AgentToolRegistry.Context(f.scope,null,f.catalog.agent("oncall",null).config(),(t,d)->{});
            var tool=f.registry.all().stream().filter(t->t.id().equals("knowledge.search")).findFirst().orElseThrow();
            f.registry.execute(tool,f.json.valueToTree(Map.of("query","default")),context);
            f.registry.execute(tool,f.json.valueToTree(Map.of("query","exact","mode","keyword")),context);
            var order=inOrder(f.search);
            order.verify(f.search).search(any(),eq("default"),eq("semantic"),anyInt(),anyInt(),eq(true));
            order.verify(f.search).search(any(),eq("exact"),eq("keyword"),anyInt(),anyInt(),eq(true));
        }
    }
    @Test void coveredDocumentReadReferencesExistingEvidenceWithoutResendingItsBody()throws Exception {
        try(var f=new Fixture(false,"react","SOURCED")) {
            var context=new AgentToolRegistry.Context(f.scope,null,f.catalog.agent("oncall",null).config(),(t,d)->{});
            var original=new AgentToolRegistry.Evidence("D-original","doc","v","doc.md","text",0,5000,"x".repeat(5000),"document");
            EvidenceContext.add(context,original,60000);
            when(f.search.read(any(),eq("doc"),eq("v"),isNull(),eq(1000),eq(2000))).thenReturn(new DocumentReadingService.Window("doc","v","doc.md",1000,3000,8000,"x".repeat(2000),true,3000));
            var read=f.registry.all().stream().filter(t->t.id().equals("documents.read")).findFirst().orElseThrow();
            var result=f.json.valueToTree(f.registry.execute(read,f.json.valueToTree(Map.of("documentId","doc","version","v","offset",1000,"maxChars",2000)),context));
            assertThat(result.has("evidence")).isFalse();
            assertThat(result.path("existingEvidenceIds").get(0).asText()).isEqualTo("D-original");
            assertThat(result.path("newChars").asInt()).isZero();
            assertThat(result.path("nextRead").path("offset").asInt()).isEqualTo(3000);
            assertThat(context.evidence).containsOnlyKeys("D-original");
            assertThat(context.evidenceChars).isEqualTo(5000);
        }
    }
    @Test void lowRerankCandidatesRemainObservableButDoNotBecomeAgentEvidence()throws Exception {
        try(var f=new Fixture(true,"react","SOURCED")){
            var low=new PlatformChunk("D-low","doc","v","existing-data","unrelated.md","Unrelated",0,0,12,"unrelated text",1.0,null,.10,"hybrid");
            when(f.search.search(any(),anyString(),anyString(),anyInt(),anyInt(),anyBoolean())).thenReturn(
                    new KnowledgeSearch.SearchResult("q","hybrid","candidates",false,List.of(),1,List.of(low),List.of(low),1,1,1,3));
            var context=new AgentToolRegistry.Context(f.scope,null,f.catalog.agent("oncall",null).config(),(t,d)->{});
            var search=f.registry.all().stream().filter(t->t.id().equals("knowledge.search")).findFirst().orElseThrow();
            var lowResult=f.json.valueToTree(f.registry.execute(search,f.json.valueToTree(Map.of("query","q")),context));
            assertThat(lowResult.path("status").asText()).isEqualTo("no_results");
            assertThat(lowResult.path("documents")).isEmpty();assertThat(context.evidence).isEmpty();

            var high=low.scored(1.0,null,.30,"hybrid");
            when(f.search.search(any(),eq("postgres"),anyString(),anyInt(),anyInt(),anyBoolean())).thenReturn(
                    new KnowledgeSearch.SearchResult("postgres","hybrid","candidates",false,List.of(),1,List.of(high),List.of(high),1,1,1,3));
            var useful=f.json.valueToTree(f.registry.execute(search,f.json.valueToTree(Map.of("query","postgres")),context));
            assertThat(useful.path("documents")).hasSize(1);assertThat(context.evidence).containsKey("D-low");
        }
    }
}
