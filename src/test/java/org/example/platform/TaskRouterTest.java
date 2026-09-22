package org.example.platform;
import org.junit.jupiter.api.Test;
import org.example.service.ChatModelFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class TaskRouterTest {
    ChatModelFactory models=mock(ChatModelFactory.class);
    TaskRouter router=new TaskRouter(models,new ObjectMapper());
    PlatformModels.AgentConfig config=new PlatformModels.AgentConfig("任意名称","","任务","你好",List.of(),List.of(),"react",8,120,.1,10,3,2000);
    @Test void everyNaturalLanguageTurnUsesSemanticRouting() {
        when(models.call(any(),eq("task-routing"),anyString(),anyString()))
                .thenReturn("{\"task\":\"GREETING\"}","{\"task\":\"INCIDENT_DIAGNOSIS\"}");
        assertThat(router.decide("hello",false,false,config,List.of()).task()).isEqualTo(TaskRouter.Task.GREETING);
        assertThat(router.decide("帮我分析这段告警",true,false,config,List.of()).task()).isEqualTo(TaskRouter.Task.INCIDENT_DIAGNOSIS);
        verify(models,times(2)).call(any(),eq("task-routing"),anyString(),anyString());
    }
    @Test void historyDoesNotOverrideCurrentIntent() {
        when(models.call(any(),eq("task-routing"),anyString(),anyString())).thenReturn("{\"task\":\"KNOWLEDGE_QUESTION\"}");
        var result=router.decide("换个话题，解释这个指标",false,true,config,List.of(Map.of("question","hello","answer","你好")));
        assertThat(result.task()).isEqualTo(TaskRouter.Task.KNOWLEDGE_QUESTION);
        assertThat(config.strategy()).isEqualTo("react");
    }
    @Test void negationAndCombinedGreetingReachSemanticRouting() {
        when(models.call(any(),eq("task-routing"),anyString(),anyString())).thenReturn("{\"task\":\"KNOWLEDGE_QUESTION\"}");
        assertThat(router.decide("不要诊断，只解释这个指标",true,true,config,List.of()).task()).isEqualTo(TaskRouter.Task.KNOWLEDGE_QUESTION);
        when(models.call(any(),eq("task-routing"),anyString(),anyString())).thenReturn("{\"task\":\"INCIDENT_DIAGNOSIS\"}");
        assertThat(router.decide("你好，帮我分析这段告警",false,false,config,List.of()).task()).isEqualTo(TaskRouter.Task.INCIDENT_DIAGNOSIS);
    }
    @Test void semanticRouterRecognizesDifferentGreetingExpressions() {
        when(models.call(any(),eq("task-routing"),anyString(),anyString())).thenReturn("{\"task\":\"GREETING\"}");
        assertThat(router.decide("hello",false,false,config,List.of()).task()).isEqualTo(TaskRouter.Task.GREETING);
        assertThat(router.decide("早上好",false,false,config,List.of()).task()).isEqualTo(TaskRouter.Task.GREETING);
        assertThat(router.decide("哟哟哟",false,false,config,List.of()).task()).isEqualTo(TaskRouter.Task.GREETING);
    }
    @Test void invalidStructuredRoutingRetriesThenClarifies() {
        when(models.call(any(),eq("task-routing"),anyString(),anyString())).thenReturn("not json");
        assertThat(router.decide("这个要怎么处理",false,false,config,List.of()).task()).isEqualTo(TaskRouter.Task.CLARIFICATION_NEEDED);
        verify(models,times(2)).call(any(),eq("task-routing"),anyString(),anyString());
    }
    @Test void casualChatIsAnIntentWithoutACapabilityGate() {
        when(models.call(any(),eq("task-routing"),anyString(),anyString())).thenReturn("{\"task\":\"CASUAL_CHAT\"}");
        var task=router.decide("谢谢你",false,false,config,List.of()).task();
        assertThat(task).isEqualTo(TaskRouter.Task.CASUAL_CHAT);
    }
    @Test void generalDiscussionCannotBypassKnowledgeLookupThroughCasualChatLabel(){
        when(models.call(any(),eq("task-routing"),anyString(),anyString())).thenReturn("{\"task\":\"CASUAL_CHAT\",\"basis\":\"GENERAL\"}");
        assertThat(router.decide("探讨一下agent的前景",false,false,config,List.of()).task()).isEqualTo(TaskRouter.Task.KNOWLEDGE_QUESTION);
    }
    @Test void greetingDoesNotBecomeRetrievalBecauseOfGeneralBasis(){
        when(models.call(any(),eq("task-routing"),anyString(),anyString())).thenReturn("{\"task\":\"GREETING\",\"basis\":\"GENERAL\"}");
        var result=router.decide("你好",false,false,config,List.of());
        assertThat(result.task()).isEqualTo(TaskRouter.Task.GREETING);assertThat(result.basis()).isEqualTo(TaskRouter.Basis.NONE);
    }
}
