package org.example.platform;

import org.junit.jupiter.api.Test;
import org.example.service.*;
import org.example.dto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExecutionModesTest {
    ObjectMapper json=new ObjectMapper();ChatModelFactory models=mock(ChatModelFactory.class);
    AgentToolRegistry registry=mock(AgentToolRegistry.class);
    List<AgentToolRegistry.Tool> tools=List.of(
        new AgentToolRegistry.Tool("knowledge.search","search_knowledge","搜索","",Map.of(),"内置",true),
        new AgentToolRegistry.Tool("documents.search","search_documents","文本搜索","",Map.of(),"内置",true),
        new AgentToolRegistry.Tool("documents.read","read_document","读取","",Map.of(),"内置",true));
    String draft="{\"findings\":[],\"actions\":[],\"missingEvidence\":[]}";
    @Test void workflowRetrievalKeepsTheOriginalAlertIdentityInEveryExpandedDirection(){
        var incident=new IncidentSnapshot("i","apdex_slo_violation_001","Apdex SLO 违反","分析当前告警",
                "{\"alerts\":[{\"alert_name\":\"ApdexSLOViolation\",\"service\":\"gitlab-web\"}]}","{\"logs\":[]}","now");
        assertThat(OnCallWorkflowExecutor.retrievalQuery(incident,"检查数据库慢查询"))
                .contains("ApdexSLOViolation","gitlab-web","检查数据库慢查询");
        assertThat(OnCallWorkflowExecutor.retrievalQuery(new IncidentSnapshot("i","user-materials","用户材料","",
                "{\"alerts\":[]}","{\"logs\":[]}","now"),"检查磁盘")).isEqualTo("检查磁盘");
    }
    AgentExecution execution(String mode,boolean report,AgentExecution.Invocation invocation){
        return execution(mode,report,6,invocation);
    }
    AgentExecution execution(String mode,boolean report,int budget,AgentExecution.Invocation invocation){
        when(models.modelName()).thenReturn("mock");when(registry.callbacks(anyList())).thenReturn(List.of());
        when(models.response(any(),eq("answer-synthesis"),any())).thenReturn(new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall("final","function","submit_answer",draft))).build()))));
        var config=new PlatformModels.AgentConfig("测试","","观察实际现场","",List.of(),List.of(),mode,budget,120,.1,10,3,2000);
        var incident=new IncidentSnapshot("i","user-materials","测试现场","error","{\"alerts\":[]}","{\"logs\":[{\"message\":\"actual error\"}]}","now");
        var context=new AgentToolRegistry.Context(new KnowledgeSearch.Scope(List.of(),Set.of(),List.of()),incident,config,(t,p)->{});
        return new AgentExecution("run","分析",config,models,json,registry,context,tools,new ArrayList<>(),new ArrayList<>(),()->{},invocation,report,System.nanoTime());
    }
    @Test void workflowWorkersAreConcurrentIsolatedAndShareOneBudget()throws Exception {
        when(models.call(any(),eq("workflow-supervisor"),anyString(),anyString())).thenReturn("{\"directions\":[\"A\",\"B\",\"C\"]}");
        when(models.call(any(),eq("workflow-worker"),anyString(),anyString())).thenReturn(draft);
        var arrived=new CountDownLatch(3);var contexts=ConcurrentHashMap.<AgentToolRegistry.Context>newKeySet();var observed=new AtomicInteger();
        AgentExecution e=execution("workflow",true,(tool,args,target)->{
            if(tool.id().equals("incident.read"))return Map.of("snapshot",target.incident.id());
            observed.incrementAndGet();contexts.add(target);
            if(tool.id().equals("knowledge.search")){
                assertThat(args.has("mode")).isFalse();
                arrived.countDown();try{assertThat(arrived.await(2,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException x){throw new CancellationException();}
                String direction=args.path("query").asText();assertThat(target.cache).isEmpty();target.cache.put("direction",direction);
                var evidence=new AgentToolRegistry.Evidence("D-"+direction,direction,"v1",direction+".md","source",0,20,"source "+direction,"document");target.evidence.put(evidence.id(),evidence);
                assertThat(target.evidence).hasSize(1);
            }
            return Map.of("status","ok");
        });
        try(var workflow=new OnCallWorkflowExecutor()){assertThat(workflow.run(e)).isEqualTo(draft);}
        verify(models).call(any(),eq("workflow-supervisor"),contains("控制台操作、工单筛选"),anyString());
        verify(models,times(3)).call(any(),eq("workflow-worker"),contains("知识库名称、资料来源和手册示例不能证明"),anyString());
        assertThat(contexts).hasSize(3);assertThat(observed).hasValue(6);assertThat(e.calls).hasValue(6);assertThat(e.context.evidence).hasSize(3);assertThat(e.context.cache).isEmpty();
        verify(models,never()).response(any(),eq("react-step"),any());verify(models,never()).call(any(),eq("plan-create"),anyString(),anyString());
        verify(models,times(3)).call(any(),eq("workflow-worker"),anyString(),anyString());
        verify(registry).callbacks(argThat(t->t.size()==1&&t.get(0).name().equals("submit_answer")));
    }
    @Test void workflowFollowupDoesNotRestartInvestigationOrSwitchToReact(){
        var e=execution("workflow",false,(t,a,c)->{assertThat(t.id()).isEqualTo("incident.read");return Map.of("snapshot",c.incident.id());});
        try(var workflow=new OnCallWorkflowExecutor()){workflow.run(e);}
        verify(models,never()).call(any(),eq("workflow-supervisor"),anyString(),anyString());verify(models,never()).response(any(),eq("react-step"),any());
    }
    @Test void workflowNewInvestigationIgnoresOldDocumentsAndFallsBackToTextSearch(){
        when(models.call(any(),eq("workflow-supervisor"),anyString(),anyString())).thenReturn("{\"directions\":[\"new problem\"]}");
        when(models.call(any(),eq("workflow-worker"),anyString(),anyString())).thenAnswer(call->{
            assertThat((String)call.getArgument(3)).contains("current observation","new-document").doesNotContain("obsolete-document");return draft;
        });
        List<String> invoked=new ArrayList<>();
        var e=execution("workflow",true,(tool,args,target)->{
            if(tool.id().equals("incident.read")){
                var observation=new AgentToolRegistry.Evidence("L1","",target.incident.id(),"现场","",0,19,"current observation","observation");
                target.evidence.put(observation.id(),observation);target.evidenceChars+=observation.content().length();return Map.of();
            }
            invoked.add(tool.id());
            if(tool.id().equals("knowledge.search")){assertThat(target.evidence.keySet()).containsExactly("L1");return Map.of("status","unavailable");}
            if(tool.id().equals("documents.search")){
                var source=new AgentToolRegistry.Evidence("D-new","new-document","v1","new.md","",0,6,"source","document");
                target.evidence.put(source.id(),source);target.evidenceChars+=source.content().length();
            }
            if(tool.id().equals("documents.read"))assertThat(args.path("documentId").asText()).isEqualTo("new-document");
            return Map.of("status","ok");
        });
        e.context.evidence.put("D-old",new AgentToolRegistry.Evidence("D-old","obsolete-document","v1","old.md","",0,3,"old","document"));e.context.evidenceChars=3;
        try(var workflow=new OnCallWorkflowExecutor()){workflow.run(e);}
        assertThat(invoked).containsExactly("knowledge.search","documents.search","documents.read");
        assertThat(e.context.evidence.keySet()).contains("D-old","D-new","L1");
    }
    @Test void workflowSmallBudgetStillAllowsEachDirectionToSearchAndRead(){
        when(models.call(any(),eq("workflow-supervisor"),anyString(),anyString())).thenReturn("{\"directions\":[\"A\",\"B\",\"C\"]}");
        when(models.call(any(),eq("workflow-worker"),anyString(),anyString())).thenReturn(draft);
        var invoked=new ConcurrentHashMap<String,List<String>>();
        var e=execution("workflow",true,4,(tool,args,target)->{
            if(tool.id().equals("incident.read"))return Map.of();
            if(tool.id().equals("knowledge.search")){
                var direction=args.path("query").asText();target.cache.put("direction",direction);
                var source=new AgentToolRegistry.Evidence("D-"+direction,direction,"v1",direction+".md","",0,6,"source","document");target.evidence.put(source.id(),source);
            }
            invoked.computeIfAbsent((String)target.cache.get("direction"),key->new ArrayList<>()).add(tool.id());return Map.of("status","ok");
        });
        try(var workflow=new OnCallWorkflowExecutor()){workflow.run(e);}
        assertThat(invoked).hasSize(2);invoked.values().forEach(calls->assertThat(calls).containsExactly("knowledge.search","documents.read"));assertThat(e.calls).hasValue(4);
        verify(models,times(2)).call(any(),eq("workflow-worker"),anyString(),anyString());
    }
    private ChatResponse response(String tool,String arguments){return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall("call","function",tool,arguments))).build())));}
    @Test void reactRepeatedRequestsWithoutEvidenceStopWithoutInventingAnAnswer(){
        var e=execution("react",false,(t,a,c)->Map.of("status","ok"));
        when(models.response(any(),eq("react-step"),any())).thenReturn(response("search_knowledge","{\"query\":\"same\"}"));
        assertThat(new ReActExecutor().run(e)).contains("调查未完成");
        assertThat(e.complete).isFalse();assertThat(e.calls).hasValue(1);assertThat(e.notices).contains("重复请求未带来新进展，已停止调查");
        verify(models,never()).response(any(),eq("answer-synthesis"),any());
    }
    @Test void attachmentPlainTextUsesExistingFinalSynthesisAndStillRejectsInvalidAnswers(){
        var e=execution("react",false,(t,a,c)->Map.of());
        e.context.materials=List.of(new AgentRuntime.Context.AttachmentContent("file","file.md","h","附件内容"));
        when(models.response(any(),eq("react-step"),any())).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("普通回答")))));
        when(models.response(any(),eq("answer-synthesis"),any())).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(draft)))));
        assertThat(new ReActExecutor().run(e)).isEqualTo(draft);
        verify(models,times(1)).response(any(),eq("answer-synthesis"),any());
        assertThat(e.calls).hasValue(0);
        when(models.response(any(),eq("answer-synthesis"),any())).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("不符合答案协议")))));
        assertThatThrownBy(()->AnswerFormatException.require(new ReActExecutor().run(e),json,"test",e.context.answerPolicy)).isInstanceOf(AnswerFormatException.class);
    }
    @Test void reactToolBudgetStopIsPartial(){
        var e=execution("react",false,1,(t,a,c)->Map.of("status","ok"));
        when(models.response(any(),eq("react-step"),any())).thenReturn(response("search_knowledge","{\"query\":\"query\"}"));
        new ReActExecutor().run(e);assertThat(e.complete).isFalse();assertThat(e.calls).hasValue(1);assertThat(e.notices).isNotEmpty();
    }
    @Test void reactExplorationTimeoutWithoutEvidenceReturnsAnHonestPartialResult(){
        var e=execution("react",false,(t,a,c)->{throw new AssertionError("no tools after timeout");});
        when(models.response(any(),eq("react-step"),any())).thenThrow(new ModelDeadline.LimitException());
        assertThat(new ReActExecutor().run(e)).contains("调查未完成");assertThat(e.complete).isFalse();assertThat(e.calls).hasValue(0);
        verify(models,never()).response(any(),eq("answer-synthesis"),any());
    }
    @Test void reactNormalSubmissionRemainsComplete(){
        var e=execution("react",false,(t,a,c)->Map.of());
        when(models.response(any(),eq("react-step"),any())).thenReturn(response("submit_answer",draft));
        assertThat(new ReActExecutor().run(e)).isEqualTo(draft);assertThat(e.complete).isTrue();verify(models,never()).response(any(),eq("answer-synthesis"),any());
    }
    @Test void failedPlanStillSynthesizesOnceWithoutGivingTheModelBusinessTools(){
        when(models.call(any(),eq("plan-create"),anyString(),anyString())).thenReturn("invalid");
        var e=execution("plan_execute_replan",false,(t,a,c)->{throw new AssertionError("invalid plan cannot execute");});
        e.context.evidence.put("D-previous",new AgentToolRegistry.Evidence("D-previous","doc","v","doc.md","",0,6,"source","document"));
        new PlanExecuteReplanExecutor().run(e);
        verify(models,times(1)).response(any(),eq("answer-synthesis"),any(Prompt.class));
        verify(models,never()).response(any(),eq("react-step"),any());
        verify(registry).callbacks(argThat(t->t.size()==1&&t.get(0).name().equals("submit_answer")));
        assertThat(e.notices).contains("计划结束：计划格式修正失败，停止调查");assertThat(e.complete).isFalse();
        var prompt=org.mockito.ArgumentCaptor.forClass(Prompt.class);verify(models).response(any(),eq("answer-synthesis"),prompt.capture());
        var options=(DashScopeChatOptions)prompt.getValue().getOptions();
        assertThat(options.getParallelToolCalls()).isFalse();
        assertThat(options.getToolChoice()).isEqualTo(Map.of("type","function","function",Map.of("name","submit_answer")));
    }
    @Test void sourcedOperationsNeverImplyVerifiedIncidentApplicability(){
        var action=new GroundedAnalysis.Action("检查 HAProxy 后端", "sudo hatop -s /run/haproxy/admin.sock", "",List.of(new GroundedAnalysis.Citation("D1","source command")));
        var answer=new AgentAnswerService.Answer(List.of(),List.of(action),List.of());
        var e=execution("workflow",true,(t,a,c)->Map.of());
        var service=new AgentAnswerService(json,new DiagnosticReportService());
        for(String output:List.of(service.renderReport(answer,e.context.incident),service.render(answer,e.context.incident))){
            assertThat(output).contains("尚未验证适用于当前现场","未确认前，不执行专属命令或配置操作");
            assertThat(output.indexOf("尚未验证适用于当前现场")).isLessThan(output.indexOf("sudo hatop"));
        }
    }
    @Test void fixedReportPreservesContradictionsAndRealSourceLabel(){
        var evidence=new GroundedAnalysis.Finding("恢复请求成功，但早前请求失败","observation",List.of(new GroundedAnalysis.Citation("L1","actual error")));
        var answer=new AgentAnswerService.Answer(List.of(evidence),List.of(),List.of("需要后续日志"),List.of(evidence));
        var e=execution("workflow",true,(t,a,c)->Map.of());
        String text=new AgentAnswerService(json,new DiagnosticReportService()).renderReport(answer,e.context.incident);
        assertThat(text).contains("现场概况","当前判断","反证与限制","建议操作","待确认项","用户提交材料","尚未确认根因").doesNotContain("模拟现场");
        assertThat(new AgentAnswerService(json,new DiagnosticReportService()).render(answer,e.context.incident))
                .startsWith("本轮尚未确认根因").contains("没有足够证据排除其他可能原因");
        assertThat(new AgentAnswerService(json,new DiagnosticReportService()).render(answer,null)).doesNotContain("本轮尚未确认根因");
    }
}
