package org.example.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MaterialEvidenceTest {
    private final ObjectMapper json=new ObjectMapper();
    @Test void appendedCounterevidenceChangesSnapshotWithoutChangingOldReport() {
        var first=new AgentRuntime.Context.AttachmentContent("initial.log","Disk 95% at 10:00");
        var initial=MaterialEvidence.incident(null,List.of(first),"diagnose",json);
        var next=new AgentRuntime.Context.AttachmentContent("counter.log","Disk 40% at 10:10; errors persist");
        var revised=MaterialEvidence.incident(initial,List.of(first,next),"revise",json);
        assertNotEquals(initial.id(),revised.id());assertEquals(1,initial.logs().size());assertEquals(2,revised.logs().size());
        assertTrue(initial.logsJson().contains("95%"));assertFalse(initial.logsJson().contains("40%"));
        var removed=MaterialEvidence.incident(revised,List.of(next),"revise",json);
        assertEquals(1,removed.logs().size());assertFalse(removed.logsJson().contains("95%"));
    }
    @Test void oldAttachmentJsonGetsStableIdentityAndHash() throws Exception {
        var old=json.readValue("{\"filename\":\"x.log\",\"content\":\"observed\"}",AgentRuntime.Context.AttachmentContent.class);
        var same=new AgentRuntime.Context.AttachmentContent("x.log","observed");
        assertNotNull(old.id());assertEquals(same.hash(),old.hash());
    }
    @Test void actualObservationSurvivesSentencePunctuationButInventedValueDoesNot() throws Exception {
        var m=new AgentRuntime.Context.AttachmentContent("incident.log","Apdex=0.62 threshold=0.8; requests=1000 slow_requests=310 http_5xx=12. WARN database query latency increased.");
        var incident=MaterialEvidence.incident(null,List.of(m),"分析现场",json);
        var f=new org.example.dto.GroundedAnalysis.Finding("Apdex=0.62，http_5xx=12。","observation",List.of(new org.example.dto.GroundedAnalysis.Citation("L1",incident.observations().get("L1"))));
        var draft=json.writeValueAsString(new org.example.dto.GroundedAnalysis(true,"",List.of(f),List.of(),List.of(),List.of()));
        var answers=new AgentAnswerService(json,new org.example.service.DiagnosticReportService());
        assertEquals(1,answers.validateReport(draft,Map.of(),incident).answer().findings().size());
        assertTrue(answers.validateReport(draft.replace("0.62","0.99"),Map.of(),incident).answer().findings().isEmpty());
    }
}
