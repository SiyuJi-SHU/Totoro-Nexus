package org.example.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.IncidentSnapshot;
import org.example.service.DiagnosticReportService;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Recorded model outputs against public runbooks and synthetic incidents, not hand-shaped happy paths. */
class LiveOutputRegressionTest {
    private final ObjectMapper json=new ObjectMapper();
    private AgentAnswerService.Validated validate(String name)throws Exception {
        try(var input=getClass().getResourceAsStream("/platform-regressions/"+name+".json")) {
            var fixture=json.readTree(input);var incident=json.treeToValue(fixture.path("incident"),IncidentSnapshot.class);
            Map<String,AgentToolRegistry.Evidence> evidence=new LinkedHashMap<>();
            for(var node:fixture.path("evidence")){var e=json.treeToValue(node,AgentToolRegistry.Evidence.class);evidence.put(e.id(),e);}
            return new AgentAnswerService(json,new DiagnosticReportService()).validate(fixture.path("auditedOutput").asText(),evidence,incident);
        }
    }
    @Test void fullIncidentValuesPreservePostgresObservationButInventedSqlIsNotPublished()throws Exception {
        var result=validate("postgres");assertThat(result.answer().findings()).anySatisfy(f->assertThat(f.text()).contains("postgres-demo-01","33909"));
        assertThat(result.answer().actions()).noneSatisfy(a->assertThat(a.command()).contains("WHERE oid = 16401"));
        assertThat(result.answer().actions()).anySatisfy(a->assertThat(a.text()).contains("关系类型"));
    }
    @Test void rateInOriginalAlertIsNotRejectedBecauseQuoteFocusesOnFailureCounts()throws Exception {
        var result=validate("sidekiq");assertThat(result.answer().findings()).anySatisfy(f->assertThat(f.text()).contains("12%","EHOSTUNREACH"));
        assertThat(result.answer().actions()).anySatisfy(a->assertThat(a.prerequisites()).contains("需具备","Sentry"));
        assertThat(result.answer().actions()).allSatisfy(a->assertThat(a.command()).doesNotContain("40888973"));
    }
    @Test void translatedPrerequisitesSurviveAndDocumentOnlyCausalityStaysUnconfirmed()throws Exception {
        var result=validate("gitaly");assertThat(result.answer().findings()).hasSize(1);
        assertThat(result.answer().findings().get(0).text()).contains("0000000000000000000000000000000000000000");
        assertThat(result.answer().actions()).anySatisfy(a->assertThat(a.prerequisites()).contains("过期或不存在"));
    }
}
