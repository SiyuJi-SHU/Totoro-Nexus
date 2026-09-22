package org.example.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.GroundedAnalysis;
import org.example.service.DiagnosticReportService;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ProgrammaticAnswerValidationTest {
    @Test void aCopiedProcedureParagraphCannotMasqueradeAsAnExecutableCommand() {
        String procedure="Override the index name in `settings.yml` and restart the service. Roll back afterwards.";
        assertThat(EvidenceProse.commandSource(procedure,procedure)).isFalse();
        assertThat(EvidenceProse.commandSource("Run `select * from pg_replication_slots`.","select * from pg_replication_slots")).isTrue();
        assertThat(EvidenceProse.commandSource("```sql\nselect 1;\n```","select 1;")).isTrue();
        assertThat(EvidenceProse.commandSource("On the alert node:\n\n    sudo gitlab-psql\n\nThen inspect locks.","sudo gitlab-psql")).isTrue();
    }

    @Test void validatorRejectsInventedCommandAndKeepsExplicitlySourcedCommand() throws Exception {
        var json=new ObjectMapper();var answers=new AgentAnswerService(json,new DiagnosticReportService());
        String source="Inspect usage, then run `select * from pg_replication_slots`. Read access is required.";
        var evidence=new AgentToolRegistry.Evidence("D-example","doc","v","guide.md","Disk",0,source.length(),source,"document");
        var citation=new GroundedAnalysis.Citation(evidence.id(),source);
        var valid=new AgentAnswerService.Answer(List.of(),List.of(new GroundedAnalysis.Action(
                "检查复制槽","select * from pg_replication_slots","需要读取权限",List.of(citation))),List.of());
        assertThat(answers.validate(json.writeValueAsString(valid),Map.of(evidence.id(),evidence),null).answer().actions()).hasSize(1);

        var invented=new AgentAnswerService.Answer(List.of(),List.of(new GroundedAnalysis.Action(
                "删除数据","rm -rf /invented/path","",List.of(citation))),List.of());
        assertThat(answers.validate(json.writeValueAsString(invented),Map.of(evidence.id(),evidence),null).answer().actions()).isEmpty();
    }
}
