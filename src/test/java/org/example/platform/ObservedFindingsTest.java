package org.example.platform;

import org.example.dto.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ObservedFindingsTest {
    @Test void separateJobLogsAreNeverRewordedAsOneJobsFailedRetries() {
        var incident=new IncidentSnapshot("s","case","队列错误","diagnose","{\"alerts\":[]}","{\"logs\":[{\"message\":\"job=a FailedToObtainLockError\"},{\"message\":\"job=b retry_scheduled retry_count=3\"}]}","now");
        var finding=new GroundedAnalysis.Finding("首次报错后重试三次仍失败","observation",List.of(new GroundedAnalysis.Citation("L1","ignored"),new GroundedAnalysis.Citation("L2","ignored")));
        var literal=ObservedFindings.literal(finding,incident);
        assertThat(literal.text()).contains("job=a FailedToObtainLockError","job=b retry_scheduled retry_count=3").doesNotContain("仍失败","首次");
        assertThat(literal.citations()).allSatisfy(c->assertThat(c.quote()).isEqualTo(incident.observations().get(c.id())));
    }
}
