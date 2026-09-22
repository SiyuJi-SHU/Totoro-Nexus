package org.example.platform;

import org.junit.jupiter.api.Test;
import org.example.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlannedExecutionTest {
    final ObjectMapper json=new ObjectMapper();final ChatModelFactory models=mock(ChatModelFactory.class);
    final AgentToolRegistry.Tool search=new AgentToolRegistry.Tool("documents.search","search_document_text","检索","",Map.of(),"内置",true);
    final AgentToolRegistry.Tool read=new AgentToolRegistry.Tool("documents.read","read_document","读取","",Map.of(),"内置",true);
    final PlatformModels.AgentConfig config=new PlatformModels.AgentConfig("助手","","按证据分析","",List.of(),List.of(),"plan_execute_replan",8,120,.1,10,3,2000);
    String step(String id,String goal,String dependencies){return "{\"id\":\""+id+"\",\"goal\":\""+goal+"\",\"dependsOn\":"+dependencies+"}";}
    String plan(String action,String... steps){return "{\"action\":\""+action+"\",\"stepComplete\":true,\"steps\":["+String.join(",",steps)+"]}";}
    String command(String query){return "{\"action\":\"execute\",\"tool\":\"search_document_text\",\"arguments\":{\"query\":\""+query+"\"}}";}
    PlannedExecution.Outcome run(PlannedExecution.Gateway gateway){return new PlannedExecution(models,json).run("问题",config,List.of(search,read),Map.of(),gateway,()->false,p->{},(t,d)->{},()->{});}
    @Test void resolvesReadArgumentsAfterTheSearchReturnsRealIds() {
        when(models.call(any(),eq("plan-create"),anyString(),anyString())).thenReturn(plan("plan",step("find","定位文档","[]"),step("read","读原文","[\"find\"]")));
        when(models.call(any(),eq("plan-execute"),anyString(),anyString())).thenAnswer(invocation->{
            var data=json.readTree(invocation.getArgument(3,String.class));
            if(data.path("current").path("id").asText().equals("find"))return command("postgres");
            assertThat(data.path("observations").toString()).contains("actual-document","v7");
            return "{\"action\":\"execute\",\"tool\":\"read_document\",\"arguments\":{\"documentId\":\"actual-document\",\"version\":\"v7\"}}";
        });
        when(models.call(any(),eq("plan-observe"),anyString(),anyString())).thenReturn("{\"action\":\"continue\",\"stepComplete\":true}","{\"action\":\"finish\",\"stepComplete\":true}");
        List<String> tools=new ArrayList<>();
        var result=run((tool,args)->{tools.add(tool.id());return tool==search?Map.of("documents",List.of(Map.of("documentId","actual-document","version","v7"))):Map.of("content","verified original");});
        assertThat(tools).containsExactly("documents.search","documents.read");assertThat(result.reason()).isEqualTo("task_satisfied");
    }
    @Test void counterevidenceReplacesOnlyRemainingGoals() {
        when(models.call(any(),eq("plan-create"),anyString(),anyString())).thenReturn(plan("plan",step("a","A","[]"),step("old","继续A","[\"a\"]")));
        when(models.call(any(),eq("plan-execute"),anyString(),anyString())).thenReturn(command("A"),command("B"));
        when(models.call(any(),eq("plan-observe"),anyString(),anyString())).thenReturn(plan("revise",step("b","改查B","[\"a\"]")),"{\"action\":\"finish\",\"stepComplete\":true}");
        var result=run((tool,args)->Map.of("content",args.path("query").asText()));
        assertThat(result.replans()).isEqualTo(1);assertThat(result.reason()).isEqualTo("task_satisfied");
        assertThat(result.plan()).anySatisfy(s->{assertThat(s.get("id")).isEqualTo("old");assertThat(s.get("status")).isEqualTo("abandoned");});
    }
    @Test void repeatedRequestIsStoppedBeforeCallingTheToolAgain() {
        when(models.call(any(),eq("plan-create"),anyString(),anyString())).thenReturn(plan("plan",step("a","A","[]"),step("b","B","[]")));
        when(models.call(any(),eq("plan-execute"),anyString(),anyString())).thenReturn(command("same"));
        when(models.call(any(),eq("plan-observe"),anyString(),anyString())).thenReturn("{\"action\":\"continue\",\"stepComplete\":true}");
        var calls=new AtomicInteger();var result=run((t,a)->{calls.incrementAndGet();return Map.of("content","result");});
        assertThat(result.reason()).isEqualTo("repeated_request");assertThat(calls).hasValue(1);
    }
    @Test void twoEmptyUnfinishedObservationsStopAfterOneRecoveryReplan() {
        when(models.call(any(),eq("plan-create"),anyString(),anyString())).thenReturn(plan("plan",step("a","A","[]")));
        when(models.call(any(),eq("plan-execute"),anyString(),anyString())).thenReturn(command("A"),command("B"));
        when(models.call(any(),eq("plan-observe"),anyString(),anyString())).thenReturn("{\"action\":\"revise\",\"stepComplete\":false,\"steps\":[{\"id\":\"b\",\"goal\":\"B\",\"dependsOn\":[]}]}");
        var result=run((t,a)->Map.of("documents",List.of()));
        assertThat(result.reason()).isEqualTo("no_progress");assertThat(result.executed()).isEqualTo(2);
    }
    @Test void emptyResultsCanCompleteSeveralEnumerationGoalsWithoutAFalseNoProgressStop() {
        when(models.call(any(),eq("plan-create"),anyString(),anyString())).thenReturn(plan("plan",step("a","检查A列表","[]"),step("b","检查B列表","[]"),step("c","检查C列表","[]")));
        when(models.call(any(),eq("plan-execute"),anyString(),anyString())).thenReturn(command("A"),command("B"),command("C"));
        when(models.call(any(),eq("plan-observe"),anyString(),anyString())).thenReturn("{\"action\":\"continue\",\"stepComplete\":true}","{\"action\":\"continue\",\"stepComplete\":true}","{\"action\":\"finish\",\"stepComplete\":true}");
        var result=run((t,a)->Map.of("matches",List.of()));
        assertThat(result.reason()).isEqualTo("task_satisfied");assertThat(result.executed()).isEqualTo(3);
        assertThat(result.plan()).allSatisfy(step->assertThat(step.get("status")).isEqualTo("completed"));
    }
    @Test void atMostTwoReplansEvenIfTheModelKeepsChangingItsMind() {
        when(models.call(any(),eq("plan-create"),anyString(),anyString())).thenReturn(plan("plan",step("a","A","[]")));
        when(models.call(any(),eq("plan-execute"),anyString(),anyString())).thenReturn(command("A"),command("B"),command("C"));
        when(models.call(any(),eq("plan-observe"),anyString(),anyString())).thenReturn(plan("revise",step("b","B","[]")),plan("revise",step("c","C","[]")),plan("revise",step("d","D","[]")));
        var result=run((t,a)->Map.of("content",a.toString()));assertThat(result.reason()).isEqualTo("replan_limit");assertThat(result.replans()).isEqualTo(2);assertThat(result.executed()).isEqualTo(3);
    }
    @Test void invalidFormatGetsOnlyOneCorrectionAndThenStops() {
        when(models.call(any(),eq("plan-create"),anyString(),anyString())).thenReturn("thinking forever");
        var result=run((t,a)->{throw new AssertionError("No execution for invalid plan");});
        assertThat(result.reason()).isEqualTo("invalid_plan");verify(models,times(2)).call(any(),eq("plan-create"),anyString(),anyString());
    }
    @Test void invalidDependencyAndUnknownExecutionToolCannotRun()throws Exception {
        assertThatThrownBy(()->new PlannedExecution(models,json).parse(json.readTree(plan("plan",step("a","A","[\"a\"]"))),Set.of())).isInstanceOf(AnswerFormatException.class);
        when(models.call(any(),eq("plan-create"),anyString(),anyString())).thenReturn(plan("plan",step("a","A","[]")));
        when(models.call(any(),eq("plan-execute"),anyString(),anyString())).thenReturn("{\"action\":\"execute\",\"tool\":\"shell\",\"arguments\":{}}");
        assertThat(run((t,a)->{throw new AssertionError();}).reason()).isEqualTo("invalid_plan");
    }
    @Test void exhaustedBudgetPreventsAnotherObservationOrTool() {
        when(models.call(any(),eq("plan-create"),anyString(),anyString())).thenReturn(plan("plan",step("a","A","[]")));
        when(models.call(any(),eq("plan-execute"),anyString(),anyString())).thenReturn(command("A"));
        var calls=new AtomicInteger();var result=new PlannedExecution(models,json).run("q",config,List.of(search),Map.of(),(t,a)->{calls.incrementAndGet();return Map.of("content","source");},()->calls.get()>=1,p->{},(t,d)->{},()->{});
        assertThat(result.reason()).isEqualTo("exploration_budget");verify(models,never()).call(any(),eq("plan-observe"),anyString(),anyString());
    }
    @Test void timeoutAndCancellationAreDifferentTerminalConditions() {
        when(models.call(any(),eq("plan-create"),anyString(),anyString())).thenThrow(new ModelDeadline.LimitException());
        assertThat(run((t,a)->Map.of()).reason()).isEqualTo("planning_timeout");
        reset(models);when(models.call(any(),eq("plan-create"),anyString(),anyString())).thenThrow(new CancellationException());
        assertThatThrownBy(()->run((t,a)->Map.of())).isInstanceOf(CancellationException.class);
    }
    @Test void completedLastStepNormalizesContinueToFinish() {
        when(models.call(any(),eq("plan-create"),anyString(),anyString())).thenReturn(plan("plan",step("a","A","[]")));
        when(models.call(any(),eq("plan-execute"),anyString(),anyString())).thenReturn(command("A"));
        when(models.call(any(),eq("plan-observe"),anyString(),anyString())).thenReturn("{\"action\":\"continue\",\"stepComplete\":true}");
        var result=run((t,a)->Map.of("content","complete"));
        assertThat(result.reason()).isEqualTo("task_satisfied");
        assertThat(result.plan()).singleElement().satisfies(s->assertThat(s.get("status")).isEqualTo("completed"));
    }
    @Test void successfulToolDoesNotCompleteGoalUntilObservedAndCanContinueTheSameGoal(){
        when(models.call(any(),eq("plan-create"),anyString(),anyString())).thenReturn(plan("plan",step("read","取得原文","[]")));
        when(models.call(any(),eq("plan-execute"),anyString(),anyString())).thenReturn(command("locate"),"{\"action\":\"execute\",\"tool\":\"read_document\",\"arguments\":{\"documentId\":\"actual-id\",\"version\":\"v1\"}}");
        when(models.call(any(),eq("plan-observe"),anyString(),anyString())).thenReturn("{\"action\":\"continue\",\"stepComplete\":false}","{\"action\":\"finish\",\"stepComplete\":true}");
        var result=run((t,a)->t==search?Map.of("documentId","actual-id","version","v1"):Map.of("content","actual original"));
        assertThat(result.reason()).isEqualTo("task_satisfied");assertThat(result.executed()).isEqualTo(2);
        assertThat(result.plan()).singleElement().satisfies(s->assertThat(s.get("status")).isEqualTo("completed"));
    }
    @Test void anUnfinishedGoalHasItsOwnThreeCallLimit(){
        when(models.call(any(),eq("plan-create"),anyString(),anyString())).thenReturn(plan("plan",step("a","查证","[]")));
        var executed=new AtomicInteger();var observed=new AtomicInteger();
        when(models.call(any(),eq("plan-execute"),anyString(),anyString())).thenAnswer(call->{
            int attempt=executed.getAndIncrement();var data=json.readTree(call.getArgument(3,String.class));
            assertThat(data.path("currentAttemptsRemaining").asInt()).isEqualTo(3-attempt);
            assertThat(data.path("remainingToolCalls").asInt()).isEqualTo(8-attempt);return command("query"+attempt);
        });
        when(models.call(any(),eq("plan-observe"),anyString(),anyString())).thenAnswer(call->{
            int attempt=observed.incrementAndGet();var data=json.readTree(call.getArgument(3,String.class));
            assertThat(data.path("currentAttemptsRemaining").asInt()).isEqualTo(3-attempt);
            assertThat(data.path("remainingToolCalls").asInt()).isEqualTo(8-attempt);return "{\"action\":\"continue\",\"stepComplete\":false}";
        });
        var result=run((t,a)->Map.of("content",a.toString()));assertThat(result.executed()).isEqualTo(3);assertThat(result.reason()).isEqualTo("step_attempt_limit");
    }
}
