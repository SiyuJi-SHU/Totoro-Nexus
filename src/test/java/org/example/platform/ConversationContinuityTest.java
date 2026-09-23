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
    @Test void transformSelectsLatestSuccessfulAnswerInsteadOfEarlierTopic()throws Exception {
        try(var f=new GeneralKnowledgeRuntimeTest.Fixture(false,"plan_execute_replan","SOURCED")) {
            var old=seed(f,"TrafficAbsent 告警的触发条件。",List.of());
            var session=f.runs.session(old.sessionId(),"alice");
            var context=f.catalog.decode(old.contextJson(),AgentRuntime.Context.class);
            var latest=f.runs.create(session,new AgentRuntime.Input("oncall",session.id(),"概述附件",null,false),context);
            f.runs.finish(latest.id(),"completed",new AgentRuntime.Result("completed","雅典娜劝忒勒马科斯寻找父亲。",new AgentAnswerService.Answer(List.of(),List.of(),List.of()),null,List.of(),List.of(),List.of(),0,1,"test","knowledge_answer","KNOWLEDGE_QUESTION","plan_execute_replan"),null);
            var failed=f.runs.create(session,new AgentRuntime.Input("oncall",session.id(),"重试附件",null,false),context);
            f.runs.finish(failed.id(),"failed",null,"format error");
            when(f.models.call(any(),eq("task-routing"),anyString(),anyString())).thenReturn("{\"task\":\"FOLLOW_UP\",\"basis\":\"TRANSFORM\"}");
            when(f.models.streamText(any(),eq("direct-response"),anyString(),anyString(),any())).thenAnswer(call->{
                var data=f.json.readTree((String)call.getArgument(3));
                assertThat(data.path("previousResponse").path("answer").asText()).contains("雅典娜").doesNotContain("TrafficAbsent");
                assertThat(data.path("earlierConversation").toString()).contains("TrafficAbsent");
                return "雅典娜提出建议。忒勒马科斯准备寻找父亲。";
            });
            follow(f,old,"把刚才的回答改写成两句话，不增加事实。");
            verifyNoInteractions(f.search);
        }
    }
    @Test void attachmentExclusionsAreScopedToTheCurrentRequest()throws Exception {
        var old=new AgentRuntime.Context.AttachmentContent("old","old.md","h","旧资料");
        var current=new AgentRuntime.Context.AttachmentContent("new","new.md","h","新资料");
        assertThat(AgentRuntime.requestMaterials("说明 TrafficAbsent 条件，不使用刚才的文学附件。",List.of(old))).isEmpty();
        assertThat(AgentRuntime.requestMaterials("不要读取 old.md，读取 new.md。",List.of(old,current))).containsExactly(current);
        assertThat(AgentRuntime.requestMaterials("读一下刚才的附件",List.of(old))).containsExactly(old);
        assertThat(AgentRuntime.requestMaterials("不要逐段翻译附件，只概述",List.of(old))).containsExactly(old);
        try(var f=new GeneralKnowledgeRuntimeTest.Fixture(false,"react","SOURCED")) {
            var seed=seed(f,"附件已上传。",List.of());
            when(f.attachments.list(anyString(),eq("alice"))).thenReturn(List.of(new SessionAttachment("old",seed.sessionId(),"old.md","text/plain",3,null,java.time.Instant.now(),"alice")));
            when(f.attachments.readContent("old","alice")).thenReturn("旧资料");
            when(f.models.response(any(),eq("react-step"),any())).thenAnswer(call->{
                Prompt prompt=call.getArgument(2);
                assertThat(prompt.getInstructions().toString()).doesNotContain("本次材料目录","read_material结果");
                return GeneralKnowledgeRuntimeTest.submit(f.draft("知识库回答"));
            });
            var result=follow(f,seed,"说明 TrafficAbsent 条件，不使用刚才的文学附件。");
            assertThat(f.runs.events(result.id(),0)).noneMatch(e->e.type().equals("tool_start")&&e.data().toString().contains("materials.read"));
            assertThat(f.catalog.decode(result.contextJson(),AgentRuntime.Context.class).attachments()).hasSize(1);
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
    @Test void attachmentFollowupReusesOnlyTheSameFileContent()throws Exception {
        try(var f=new GeneralKnowledgeRuntimeTest.Fixture(false,"react","SOURCED")) {
            String content="附件事实：项目代号为 MAPLE。",fileId="file-1",hash=org.example.service.KnowledgeFiles.digest(content);
            var evidence=new AgentToolRegistry.Evidence("U-original",fileId,hash,"notes.md","附件",0,content.length(),content,"attachment");
            var old=seed(f,"已经读完 notes.md，项目代号为 MAPLE。",List.of(evidence));
            when(f.attachments.list(anyString(),eq("alice"))).thenReturn(List.of(new SessionAttachment(fileId,old.sessionId(),"notes.md","text/markdown",content.length(),null,java.time.Instant.now(),"alice")));
            when(f.attachments.readContent(fileId,"alice")).thenReturn(content);
            when(f.models.call(any(),eq("task-routing"),anyString(),anyString())).thenReturn("{\"task\":\"FOLLOW_UP\",\"basis\":\"SOURCED\"}");
            when(f.models.response(any(),eq("react-step"),any())).thenReturn(GeneralKnowledgeRuntimeTest.submit(f.catalog.encode(
                    Map.of("answerText","项目代号为 MAPLE。","citations",List.of(Map.of("id","U-original","spanIds",List.of("s0"))),"missingEvidence",List.of()))));
            var reused=follow(f,old,"我发你的附件里项目代号是什么？");
            assertThat(f.runs.events(reused.id(),0)).noneMatch(e->e.type().equals("tool_start"));
            assertThat(f.catalog.decode(reused.resultJson(),AgentRuntime.Result.class).answer().citations()).singleElement().satisfies(c->assertThat(c.quote()).isEqualTo(content));
            String updated="附件事实：项目代号更新为 CEDAR。";
            when(f.attachments.readContent(fileId,"alice")).thenReturn(updated);
            when(f.models.response(any(),eq("react-step"),any())).thenReturn(GeneralKnowledgeRuntimeTest.submit(f.draft("项目代号更新为 CEDAR。")));
            var changed=follow(f,reused,"再读一下附件，项目代号是什么？");
            assertThat(f.runs.events(changed.id(),0)).anyMatch(e->e.type().equals("tool_start"));
            assertThat(f.catalog.decode(changed.resultJson(),AgentRuntime.Result.class).evidence())
                    .noneMatch(e->e.id().equals("U-original")).anySatisfy(e->assertThat(e.content()).contains("CEDAR"));
        }
    }
}
