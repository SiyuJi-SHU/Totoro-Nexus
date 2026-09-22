package org.example.platform;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import java.util.*;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConversationContinuityTest {
    private static AgentRunStore.Run seed(GeneralKnowledgeRuntimeTest.Fixture f,String text,List<AgentToolRegistry.Evidence> evidence) {
        var session=f.runs.openSession("alice","oncall",null,"原问题");
        var input=new AgentRuntime.Input("oncall",session.id(),"原问题",null,false);
        var context=new AgentRuntime.Context(f.scope,null,f.registry.allowed(f.catalog.agent("oncall",null).config()),List.of(),List.of());
        var run=f.runs.create(session,input,context);
        var answer=new AgentAnswerService.Answer(List.of(),List.of(),List.of());
        f.runs.finish(run.id(),"completed",new AgentRuntime.Result("completed",text,answer,null,evidence,List.of(),List.of(),0,1,"test","knowledge_answer","KNOWLEDGE_QUESTION",f.catalog.agent("oncall",null).config().strategy()),null);
        return f.runs.get(run.id());
    }
    private static AgentRunStore.Run follow(GeneralKnowledgeRuntimeTest.Fixture f,AgentRunStore.Run old,String question)throws Exception {
        var run=f.runtime.start("alice",new AgentRuntime.Input("oncall",old.sessionId(),question,null,false));
        long until=System.nanoTime()+8_000_000_000L;
        while(AgentRunStore.active(run.status())&&System.nanoTime()<until){Thread.sleep(15);run=f.runs.get(run.id());}
        assertThat(run.status()).as("%s",run.error()).isEqualTo("completed");return run;
    }
    @Test void transformReceivesReportTailAcrossAllStrategiesWithoutTools()throws Exception {
        for(String strategy:List.of("workflow","react","plan_execute_replan"))try(var f=new GeneralKnowledgeRuntimeTest.Fixture(false,strategy,"SOURCED")) {
            var old=seed(f,"现场信息。".repeat(400)+"待确认项：上游健康状态；近期变更；具体受影响服务。",List.of());
            when(f.models.call(any(),eq("task-routing"),anyString(),anyString())).thenReturn("{\"task\":\"FOLLOW_UP\",\"basis\":\"TRANSFORM\"}");
            when(f.models.streamText(any(),eq("direct-response"),anyString(),anyString(),any())).thenAnswer(call->{
                assertThat((String)call.getArgument(3)).contains("具体受影响服务");
                Consumer<String> delta=call.getArgument(4);delta.accept("确认上游健康状态、近期变更及受影响服务。");return "确认上游健康状态、近期变更及受影响服务。";
            });
            follow(f,old,"把上一轮待确认项整理成交接事项。");
            verify(f.models,never()).response(any(),anyString(),any());verifyNoInteractions(f.search);
        }
    }
    @Test void followupEvidenceUsesSameSpanProtocolAndSurvivesFailedAttempts()throws Exception {
        try(var f=new GeneralKnowledgeRuntimeTest.Fixture(false,"react","SOURCED")) {
            String id="D-12345678901234567890",fact="Traffic loss alone does not prove a service outage.";
            var evidence=new AgentToolRegistry.Evidence(id,"doc","v","traffic.md","Traffic",0,fact.length(),fact,"document");
            var old=seed(f,"流量消失不证明服务宕机。["+id+"]",List.of(evidence));
            var transformed=f.runs.create(f.runs.session(old.sessionId(),"alice"),new AgentRuntime.Input("oncall",old.sessionId(),"简短概括",null,false),f.catalog.decode(old.contextJson(),AgentRuntime.Context.class));
            f.runs.finish(transformed.id(),"completed",new AgentRuntime.Result("completed","不能直接证明服务宕机。",new AgentAnswerService.Answer(List.of(),List.of(),List.of()),null,List.of(),List.of(),List.of(),0,1,"test","chat_reply","KNOWLEDGE_QUESTION","react"),null);
            for(int i=0;i<5;i++){
                var retry=f.runs.create(f.runs.session(old.sessionId(),"alice"),new AgentRuntime.Input("oncall",old.sessionId(),"重试",null,false),f.catalog.decode(old.contextJson(),AgentRuntime.Context.class));
                f.runs.finish(retry.id(),"timed_out",null,"timeout");
            }
            when(f.models.call(any(),eq("task-routing"),anyString(),anyString())).thenReturn("{\"task\":\"FOLLOW_UP\",\"basis\":\"SOURCED\"}");
            when(f.models.response(any(),eq("react-step"),any())).thenAnswer(call->{
                Prompt p=call.getArgument(2);assertThat(p.getInstructions().toString()).contains("[s0]",fact,"citationProtocol");
                return GeneralKnowledgeRuntimeTest.submit(f.catalog.encode(Map.of("answerText","不能证明宕机。","citations",List.of(Map.of("id",id,"spanIds",List.of("s0"))),"missingEvidence",List.of())));
            });
            var result=f.catalog.decode(follow(f,old,"所以能证明宕机吗？").resultJson(),AgentRuntime.Result.class);
            assertThat(result.answer().citations()).singleElement().satisfies(c->assertThat(c.quote()).isEqualTo(fact));
        }
    }
    @Test void planningAndAnsweringSeeTheSamePreviousQuestion()throws Exception {
        try(var f=new GeneralKnowledgeRuntimeTest.Fixture(false,"plan_execute_replan","SOURCED")) {
            var old=seed(f,"先检查Triage Dashboard的Gitaly p95 latency。",List.of());
            when(f.models.call(any(),eq("task-routing"),anyString(),anyString())).thenReturn("{\"task\":\"FOLLOW_UP\",\"basis\":\"SOURCED\"}");
            when(f.models.call(any(),eq("plan-create"),anyString(),anyString())).thenAnswer(call->{
                assertThat((String)call.getArgument(3)).contains("Gitaly p95 latency","conversation");
                return "{\"action\":\"finish\",\"reason\":\"已有上下文\"}";
            });
            when(f.models.response(any(),eq("answer-synthesis"),any())).thenReturn(GeneralKnowledgeRuntimeTest.submit(f.draft("上轮检查的是Gitaly p95 latency。")));
            follow(f,old,"刚才我们讨论的是哪个检查？");
        }
    }
}
