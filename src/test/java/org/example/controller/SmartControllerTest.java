package org.example.controller;

import org.example.service.*;
import org.example.dto.*;
import org.junit.jupiter.api.Test;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

class SmartControllerTest {
    @Test void explicitNoMatchIsPersistedAndProviderFailureIsNotASuccessEvent() throws Exception {
        var aiOps=mock(AiOpsService.class);var smart=mock(SmartAiOpsService.class);
        var models=mock(ChatModelFactory.class);var conversations=mock(ConversationStore.class);
        var meters=new SimpleMeterRegistry();var controller=new DiagnosisController(aiOps,smart,models,conversations,meters);
        var snapshot=new IncidentSnapshot(UUID.randomUUID().toString(),"s1","scene","unrelated","{\"alerts\":[{\"service\":\"db\"}]}","{\"logs\":[]}","2026-09-14T00:00:00Z");
        var result=new AiOpsService.DiagnosisOutcome(snapshot,"no_match","无匹配","现场事实",GroundedAnalysis.insufficient("db","无匹配"),List.of());
        when(aiOps.capture(eq("s1"),eq("unrelated"),any())).thenReturn(snapshot);
        when(smart.diagnose(same(snapshot),any(),any())).thenReturn(result);
        var request=new SmartDiagnosisRequest();request.setSymptoms("unrelated");request.setScenarioId("s1");request.setSessionId("chat-1");
        try {
            var events=controller.smart(request).collectList().block(Duration.ofSeconds(3));
            assertThat(events).hasSize(1);assertThat(events.get(0).event()).isEqualTo("result");
            assertThat(events.get(0).data()).contains("\"status\":\"no_match\"","\"primary\":null");
            verify(conversations).save("chat-1",result);
            when(smart.diagnose(same(snapshot),any(),any())).thenThrow(new IllegalStateException("offline"));
            var failure=controller.smart(request).collectList().block(Duration.ofSeconds(3));
            assertThat(failure).hasSize(1);assertThat(failure.get(0).event()).isEqualTo("error");
            verify(conversations,times(1)).save(anyString(),any());
            assertThatThrownBy(()->controller.smart(new SmartDiagnosisRequest(" "))).hasMessageContaining("400");
        } finally {controller.close();meters.close();}
    }
}
