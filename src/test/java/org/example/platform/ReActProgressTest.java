package org.example.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.service.ChatModelFactory;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReActProgressTest {
    final ObjectMapper json=new ObjectMapper();
    ChatResponse call(String id,String name,String args){return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall(id,"function",name,args))).build())));}
    @Test void forwardProgressResetsRepeatGuardAndEquivalentJsonDoesNotReadAgain()throws Exception {
        var registry=mock(AgentToolRegistry.class);var models=mock(ChatModelFactory.class);when(models.modelName()).thenReturn("test");
        var config=new PlatformModels.AgentConfig("test","","","",List.of(),List.of(),"react",8,120,.1,20,10,4000);
        List<String> events=new ArrayList<>();var context=new AgentToolRegistry.Context(new KnowledgeSearch.Scope(List.of(),Set.of(),List.of()),null,config,(type,data)->events.add(type));
        var read=new AgentToolRegistry.Tool("documents.read","read_document","Read","Read",Map.of(),"builtin",true);
        String first="{\"documentId\":\"doc\",\"version\":\"v\",\"offset\":0}",reordered="{\"offset\":0,\"version\":\"v\",\"documentId\":\"doc\"}";
        String next="{\"documentId\":\"doc\",\"version\":\"v\",\"offset\":6000}";
        String submitted="{\"findings\":[],\"actions\":[],\"missingEvidence\":[]}";
        when(models.response(any(),eq("react-step"),any())).thenReturn(call("1","read_document",first),call("2","read_document",reordered),call("3","read_document",next),call("4","read_document",next),call("5","submit_answer",submitted));
        var e=new AgentExecution("run","summarize",config,models,json,registry,context,List.of(read),new ArrayList<>(),new ArrayList<>(),()->{},(t,a,c)->Map.of("nextRead",Map.of("documentId","doc","version","v","offset",a.path("offset").asInt()+6000),"evidence",Map.of("start",a.path("offset").asInt(),"end",a.path("offset").asInt()+6000),"totalChars",18000),false,System.nanoTime());
        assertEquals(submitted,new ReActExecutor().run(e));assertEquals(2,e.calls.get());assertTrue(e.complete);
        assertEquals(2,events.stream().filter(s->s.equals("tool_repeat")).count());
        String results=e.messages.stream().filter(m->m instanceof ToolResponseMessage).map(Object::toString).reduce("",String::concat);
        assertTrue(results.contains("nextRead"));assertTrue(results.contains("previousRange"));
        verify(models,never()).response(any(),eq("answer-synthesis"),any());
    }
    @Test void modelReadSchemaRequiresExplicitPositionWithoutBreakingLegacyDirectCalls() {
        var tool=new AgentToolRegistry.Tool("documents.read","read_document","Read","Read",Map.of("required",List.of("documentId","version")),"builtin",true);
        assertEquals(List.of("documentId","version","offset"),AgentToolRegistry.modelSchema(tool).get("required"));
        assertEquals(List.of("documentId","version"),tool.schema().get("required"));
    }
    @Test void interruptedInvestigationCannotAskModelToInventWhySourcesAreAbsent()throws Exception {
        var registry=mock(AgentToolRegistry.class);var models=mock(ChatModelFactory.class);
        var config=new PlatformModels.AgentConfig("test","","","",List.of(),List.of(),"react",8,120,.1,20,10,4000);
        var context=new AgentToolRegistry.Context(new KnowledgeSearch.Scope(List.of(),Set.of(),List.of()),null,config,(type,data)->{});
        var e=new AgentExecution("run","summarize",config,models,json,registry,context,List.of(),new ArrayList<>(),new ArrayList<>(),()->{},(t,a,c)->Map.of(),false,System.nanoTime());
        e.calls.set(8);
        var answer=json.readTree(new ReActExecutor().run(e));
        assertFalse(e.complete);assertTrue(answer.path("findings").isEmpty());
        assertTrue(answer.path("missingEvidence").get(0).asText().contains("调查未完成"));
        verifyNoInteractions(models);
    }
}
