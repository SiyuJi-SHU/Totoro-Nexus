package org.example.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.GroundedAnalysis;
import org.example.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import java.util.*;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConversationAnswerTest {
    @Test void directoryReturnsConfiguredNamesAndOnlyAuthorizedMetadata()throws Exception {
        try(var f=new GeneralKnowledgeRuntimeTest.Fixture(false,"react","SOURCED")) {
            f.catalog.saveKnowledgeBase("existing-knowledge","Gitlab运维知识库","运维资料","hybrid",List.of("existing-data"));
            f.catalog.saveDataset("existing-data","运维文档","",null);
            var hidden=f.catalog.saveDataset(null,"机密数据集","",null);
            f.catalog.saveKnowledgeBase(null,"未授权知识库","","hybrid",List.of(hidden.id()));
            var scope=new KnowledgeSearch.Scope(f.scope.knowledgeBaseIds(),f.scope.datasetIds(),documents(101));
            var context=new AgentToolRegistry.Context(scope,null,f.catalog.agent("oncall",null).config(),(t,d)->{});
            var tool=f.registry.all().stream().filter(t->t.id().equals("knowledge.list")).findFirst().orElseThrow();
            var result=f.json.valueToTree(f.registry.execute(tool,f.json.createObjectNode(),context));
            assertThat(result.path("knowledgeBases").get(0).path("name").asText()).isEqualTo("Gitlab运维知识库");
            assertThat(result.path("datasets").get(0).path("name").asText()).isEqualTo("运维文档");
            assertThat(result.path("documents").get(0).path("datasetId").asText()).isEqualTo("existing-data");
            assertThat(result.path("total").asInt()).isEqualTo(101);
            assertThat(result.path("returned").asInt()).isEqualTo(100);
            assertThat(result.path("truncated").asBoolean()).isTrue();
            assertThat(result.toString()).doesNotContain("机密数据集","未授权知识库",hidden.id());
            // A rename is metadata, not a change to document identity or evidence permissions.
            f.catalog.saveKnowledgeBase("existing-knowledge","改名后的知识库","","hybrid",List.of("existing-data"));
            var next=new AgentToolRegistry.Context(scope,null,context.config,(t,d)->{});
            assertThat(f.json.valueToTree(f.registry.execute(tool,f.json.createObjectNode(),next)).path("knowledgeBases").get(0).path("name").asText()).isEqualTo("改名后的知识库");
            assertThat(context.evidence).isEmpty();
        }
    }
    private static ChatResponse call(String name,String args) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(
                List.of(new AssistantMessage.ToolCall(UUID.randomUUID().toString(),"function",name,args))).build())));
    }
    private static List<PlatformModels.DocumentVersion> documents(int count) {
        return IntStream.rangeClosed(1,count).mapToObj(i->new PlatformModels.DocumentVersion("doc-"+i,"existing-data","manual-"+i+".md","v"+i,"hash","source",200,1,"test","active")).toList();
    }
    private static void listThenAnswer(GeneralKnowledgeRuntimeTest.Fixture f,String strategy,String draft) {
        if(strategy.equals("react"))when(f.models.response(any(),eq("react-step"),any())).thenReturn(call("list_knowledge_sources","{}"),call("submit_answer",draft));
        else {
            when(f.models.call(any(),eq("plan-create"),anyString(),anyString())).thenReturn("{\"action\":\"plan\",\"steps\":[{\"id\":\"list\",\"goal\":\"列出授权文档\",\"dependsOn\":[]}]}");
            when(f.models.call(any(),eq("plan-execute"),anyString(),anyString())).thenReturn("{\"action\":\"execute\",\"tool\":\"list_knowledge_sources\",\"arguments\":{}}");
            when(f.models.call(any(),eq("plan-observe"),anyString(),anyString())).thenReturn("{\"action\":\"finish\",\"stepComplete\":true,\"reason\":\"已取得目录结果\"}");
            when(f.models.response(any(),eq("answer-synthesis"),any())).thenReturn(call("submit_answer",draft));
        }
    }
    @Test void thirtyFiveAuthorizedDocumentsReachTheFinalAnswerInBothAutonomousModes()throws Exception {
        for(String strategy:List.of("react","plan_execute_replan"))try(var f=new GeneralKnowledgeRuntimeTest.Fixture(false,strategy,"SOURCED")) {
            var docs=documents(35);when(f.search.scope(anyList())).thenReturn(new KnowledgeSearch.Scope(List.of("existing-knowledge"),Set.of("existing-data"),docs));
            String body="当前授权文档共35篇：\n"+String.join("\n",docs.stream().map(PlatformModels.DocumentVersion::path).toList());
            listThenAnswer(f,strategy,f.draft(body));
            var run=f.run("完整列出授权文档");var result=f.catalog.decode(run.resultJson(),AgentRuntime.Result.class);
            assertThat(run.status()).as("%s: %s",strategy,run.error()).isEqualTo("completed");
            assertThat(result.answer().answerText()).isEqualTo(body);assertThat(result.evidence()).isEmpty();assertThat(result.toolCalls()).isEqualTo(1);
            docs.forEach(doc->assertThat(result.text()).contains(doc.path()));
            assertThat(result.text()).doesNotContain("未取得支持回答","private.md");
            var prompts=org.mockito.ArgumentCaptor.forClass(Prompt.class);verify(f.models,atLeastOnce()).response(any(),anyString(),prompts.capture());
            assertThat(prompts.getAllValues().get(prompts.getAllValues().size()-1).getInstructions().toString()).contains("manual-35.md","total");
            verify(f.search,never()).read(any(),anyString(),anyString(),any(),any(),any());
        }
    }
    @Test void anEmptyDirectoryIsAValidAnswerRatherThanMissingEvidence()throws Exception {
        for(String strategy:List.of("react","plan_execute_replan"))try(var f=new GeneralKnowledgeRuntimeTest.Fixture(false,strategy,"SOURCED")) {
            listThenAnswer(f,strategy,f.draft("本次会话的授权范围内没有文档。"));
            var run=f.run("有哪些文档？");assertThat(run.status()).isEqualTo("completed");
            assertThat(f.catalog.decode(run.resultJson(),AgentRuntime.Result.class).text()).contains("没有文档").doesNotContain("证据不足");
        }
    }
    @Test void navigationToolsCanAnswerWithoutReadingTheDocumentBody()throws Exception {
        try(var f=new GeneralKnowledgeRuntimeTest.Fixture(false,"react","SOURCED")) {
            when(f.search.findDocuments(any(),eq("manual"))).thenReturn(List.of(Map.of("documentId","doc","version","v","sourceFile","manual.md","contentLength",200)));
            when(f.search.sections(any(),eq("doc"),eq("v"))).thenReturn(List.of(new DocumentReadingService.Section("s0","安装",1,0,90),new DocumentReadingService.Section("s1","使用",1,90,200)));
            when(f.models.response(any(),eq("react-step"),any())).thenReturn(call("find_documents","{\"query\":\"manual\"}"),call("list_document_sections","{\"documentId\":\"doc\",\"version\":\"v\"}"),call("submit_answer",f.draft("manual.md 包含两个章节：安装、使用。")));
            var run=f.run("找到manual文件并列出章节");var result=f.catalog.decode(run.resultJson(),AgentRuntime.Result.class);
            assertThat(run.status()).isEqualTo("completed");assertThat(result.text()).contains("安装","使用");assertThat(result.evidence()).isEmpty();
            verify(f.search,never()).read(any(),anyString(),anyString(),any(),any(),any());
        }
    }
    @Test void toolFailureRemainsAnExplicitFailureToObtainDataNotProofOfAnEmptyDirectory()throws Exception {
        try(var f=new GeneralKnowledgeRuntimeTest.Fixture(false,"react","SOURCED")) {
            when(f.search.findDocuments(any(),anyString())).thenThrow(new IllegalStateException("lookup offline"));
            String draft=f.catalog.encode(AnswerSubmission.missing(AnswerPolicy.CONVERSATIONAL,"文件定位工具调用失败，本次无法确认文档列表。"));
            when(f.models.response(any(),eq("react-step"),any())).thenReturn(call("find_documents","{\"query\":\"manual\"}"),call("submit_answer",draft));
            var run=f.run("查找manual文档");var result=f.catalog.decode(run.resultJson(),AgentRuntime.Result.class);
            assertThat(run.status()).isEqualTo("insufficient_evidence");assertThat(result.text()).contains("工具调用失败").doesNotContain("没有文档");
            assertThat(f.runs.events(run.id(),0)).anySatisfy(event->assertThat(event.type()).isEqualTo("tool_error"));
        }
    }
    @Test void budgetStopStillSynthesizesPreviouslyReturnedMetadata()throws Exception {
        try(var f=new GeneralKnowledgeRuntimeTest.Fixture(false,"react","SOURCED")) {
            var context=new AgentToolRegistry.Context(f.scope,null,f.catalog.agent("oncall",null).config(),(t,d)->{});context.answerPolicy=AnswerPolicy.CONVERSATIONAL;
            var tool=f.registry.all().stream().filter(t->t.id().equals("knowledge.list")).findFirst().orElseThrow();
            var execution=new AgentExecution("test","list",context.config,f.models,f.json,f.registry,context,List.of(tool),new ArrayList<>(),new ArrayList<>(),()->{},(t,a,c)->Map.of("documents",List.of("manual.md")),false,System.nanoTime());
            execution.call(tool,f.json.createObjectNode());execution.calls.set(context.config.maxToolCalls());execution.complete=false;
            String answer=f.draft("已取得 manual.md；调查预算已用完，未完成其余检查。");
            when(f.models.response(any(),eq("answer-synthesis"),any())).thenReturn(call("submit_answer",answer));
            assertThat(execution.finish()).isEqualTo(answer);assertThat(execution.complete).isFalse();
            var prompt=org.mockito.ArgumentCaptor.forClass(Prompt.class);verify(f.models).response(any(),eq("answer-synthesis"),prompt.capture());
            assertThat(prompt.getValue().getInstructions().toString()).contains("manual.md");
        }
    }
    @Test void conversationalSubmissionCannotBypassAnIncidentTaskRegardlessOfExecutor()throws Exception {
        for(String strategy:List.of("react","plan_execute_replan"))try(var f=new GeneralKnowledgeRuntimeTest.Fixture(false,strategy,"SOURCED")) {
            when(f.models.call(any(),eq("task-routing"),anyString(),anyString())).thenReturn("{\"task\":\"INCIDENT_DIAGNOSIS\",\"basis\":\"SOURCED\"}");
            listThenAnswer(f,strategy,f.draft("故障已经确认，执行重启。"));
            var run=f.run("诊断当前故障");assertThat(run.status()).isEqualTo("failed");assertThat(run.resultJson()).isNull();
            assertThat(run.error()).contains("结构化");
        }
    }
    @Test void optionalCitationValidationKeepsTheBodyButDoesNotPublishFakeSourceLinks()throws Exception {
        var json=new ObjectMapper();var answers=new AgentAnswerService(json,new DiagnosticReportService());String id="D-12345678901234567890";
        String draft=json.writeValueAsString(Map.of("answerText","工具返回了一个章节。 ["+id+"]","citations",List.of(Map.of("id",id,"spanIds",List.of("s0"))),"missingEvidence",List.of()));
        AnswerFormatException.require(draft,json,"test",AnswerPolicy.CONVERSATIONAL);
        var checked=answers.acceptConversation(SourceSpans.resolveDraft(draft,Map.of(),json),Map.of());
        assertThat(checked.answer().answerText()).contains("一个章节").doesNotContain(id);assertThat(checked.answer().citations()).isEmpty();assertThat(checked.notices()).isNotEmpty();
        var strict=new AgentAnswerService.Answer(List.of(new GroundedAnalysis.Finding("故障已确认","supported",List.of(new GroundedAnalysis.Citation(id,"伪造原文")))),List.of(),List.of());
        assertThat(answers.validate(json.writeValueAsString(strict),Map.of(),null).answer().findings()).isEmpty();
    }
    @Test void oldStoredAnswersStillDeserializeAndRender()throws Exception {
        var json=new ObjectMapper();var answers=new AgentAnswerService(json,new DiagnosticReportService());
        var old=json.readValue("{\"findings\":[],\"actions\":[],\"missingEvidence\":[],\"generalExplanation\":\"历史回答\"}",AgentAnswerService.Answer.class);
        assertThat(old.answerText()).isEmpty();assertThat(old.citations()).isEmpty();assertThat(answers.render(old,null)).contains("历史回答");
    }
    @Test void realOptionalCitationsSurviveSpanExpansionAndRemainRequiredInEvaluation()throws Exception {
        var json=new ObjectMapper();var answers=new AgentAnswerService(json,new DiagnosticReportService());String id="D-12345678901234567890",content="发布前必须完成测试。";
        var source=new AgentToolRegistry.Evidence(id,"doc","v","release.md","发布",0,content.length(),content,"document");
        String draft=json.writeValueAsString(Map.of("answerText",content,"citations",List.of(Map.of("id",id,"spanIds",List.of("s0"))),"missingEvidence",List.of()));
        var accepted=answers.acceptConversation(SourceSpans.resolveDraft(draft,Map.of(id,source),json),Map.of(id,source)).answer();
        assertThat(accepted.allCitations()).containsExactly(new GroundedAnalysis.Citation(id,content));assertThat(answers.render(accepted,null)).contains(id);
        var result=new AgentRuntime.Result("completed",answers.render(accepted,null),accepted,null,List.of(source),List.of(),List.of(),1,1,"test","knowledge_answer","KNOWLEDGE_QUESTION","react");
        String review="""
                {"checks":{"answerCorrectness":{"status":"passed","reason":"正确"},"citationSupport":{"status":"not_applicable","reason":"忽略引用"},"observationDiscipline":{"status":"not_applicable","reason":"不是诊断"},"actionSupport":{"status":"not_applicable","reason":"无操作"}}}
                """;
        assertThatThrownBy(()->PlatformEvaluation.checkedReview(review,json,result)).isInstanceOf(AnswerFormatException.class);
    }
}
