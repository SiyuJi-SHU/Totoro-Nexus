package org.example.platform;
import org.example.dto.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class IncidentActionTargetsTest {
    private final IncidentSnapshot incident=new IncidentSnapshot("id","scene","test","","{\"alerts\":[{\"service\":\"probe\",\"instance\":\"probe-demo.internal\"}]}","{\"logs\":[]}","now");
    @Test void copiedRunbookDeploymentDoesNotBecomeCurrentTarget(){var action=new GroundedAnalysis.Action("读取探针日志","ssh probe-prod.internal","需访问 probe-prod.internal",List.of());var bound=IncidentActionTargets.bind(action,incident);assertThat(bound.command()).isEmpty();assertThat(bound.prerequisites()).contains("尚未与本次现场对应");assertThat(bound.text()).isEqualTo(action.text());}
    @Test void knownIncidentTargetIsPreserved(){var action=new GroundedAnalysis.Action("读取探针日志","ssh probe-demo.internal","",List.of());assertThat(IncidentActionTargets.bind(action,incident)).isEqualTo(action);}
    @Test void codeAndFileNamesAreNotHosts(){var action=new GroundedAnalysis.Action("检查配置","cat config.yml","根据 manual.md 执行",List.of());assertThat(IncidentActionTargets.bind(action,incident)).isEqualTo(action);}
}
