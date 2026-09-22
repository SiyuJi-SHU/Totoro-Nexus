package org.example.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class DiagnosticSourceRenderingTest {
    @Test void withdrawingAnUnsupportedCausePreservesCounterevidenceWithoutPublishingTheCause() {
        var incident=new IncidentSnapshot("s","user-materials","material","","{\"alerts\":[]}","{\"logs\":[{\"message\":\"database p99=2ms; application CPU=99%; queue delay=900ms\"}]}","now");
        var finding=new GroundedAnalysis.Finding("CPU饱和导致延迟", "hypothesis",List.of(new GroundedAnalysis.Citation("L1",incident.observations().get("L1"))));
        var checked=new AgentAnswerService.Validated(new AgentAnswerService.Answer(List.of(finding),List.of(),List.of()),0,List.of());
        var json=new ObjectMapper();
        var result=DiagnosticSourceRendering.render(checked,json.valueToTree(Map.of("findings",List.of(finding))),json.valueToTree(Map.of()),Map.of(),incident,true);
        assertThat(result.answer().findings()).singleElement().satisfies(f->{
            assertThat(f.certainty()).isEqualTo("observation");
            assertThat(f.text()).contains("未经独立核实","database p99=2ms; application CPU=99%; queue delay=900ms").doesNotContain("导致");
            assertThat(f.citations()).extracting(GroundedAnalysis.Citation::id).containsExactly("L1");
        });
        assertThat(result.rejectedItems()).isEqualTo(1);
    }
    @Test void preservesConditionsAfterTheSelectedCommandInsteadOfTruncatingAtTheNextParagraph() {
        String source="Check `du -sh /data/pg_xlog`.\n\nPostgreSQL 11+ uses pg_wal.\n\nDo not remove files manually.";
        var e=new AgentToolRegistry.Evidence("D-doc","doc","v","guide.md","Space",0,source.length(),source,"document");
        assertThat(DiagnosticSourceRendering.context(e,"Check `du -sh /data/pg_xlog`.")).isEqualTo(source);
    }
    @Test void aSourcedHypothesisShowsItsActualIncidentRecordAsWellAsItsDocumentBasis() {
        var incident=new IncidentSnapshot("s","user-materials","material","","{\"alerts\":[]}","{\"logs\":[{\"message\":\"application CPU=99%; queue delay=900ms\"}]}","now");
        String source="Traffic spikes can exhaust CPU resources.";
        var document=new AgentToolRegistry.Evidence("D-doc","doc","v","guide.md","Causes",0,source.length(),source,"document");
        var finding=new GroundedAnalysis.Finding("资源瓶颈待验证","hypothesis",List.of(new GroundedAnalysis.Citation("L1",incident.observations().get("L1")),new GroundedAnalysis.Citation("D-doc",source)));
        var checked=new AgentAnswerService.Validated(new AgentAnswerService.Answer(List.of(finding),List.of(),List.of()),0,List.of());
        var json=new ObjectMapper();
        var result=DiagnosticSourceRendering.render(checked,json.valueToTree(Map.of("findings",List.of(finding))),json.valueToTree(Map.of("findings",List.of(Map.of("index",0,"text",finding.text())))),Map.of("D-doc",document),incident,true).answer();
        assertThat(result.findings()).hasSize(2);
        assertThat(result.findings().get(0).certainty()).isEqualTo("observation");
        assertThat(result.findings().get(0).text()).contains("CPU=99%","queue delay=900ms");
        assertThat(result.findings().get(1).text()).contains(source,"尚不能据此确认");
    }
    @Test void anAuditorsMistakenParaphraseCannotChangeAConditionalProcedureOrInventLogTiming() {
        String source="The next day's index creates the new mapping.\n\nIf you need to fix the issue before that, override the index and restart. Roll back that change afterwards.";
        String quoted=source.substring(source.indexOf("If you"));var e=new AgentToolRegistry.Evidence("D-doc","doc","v","guide.md","Recovery",0,source.length(),source,"document");
        var citation=new GroundedAnalysis.Citation(e.id(),quoted);
        var finding=new GroundedAnalysis.Finding("任务重试三次仍失败，可能由格式变化导致","hypothesis",List.of(citation));
        var action=new GroundedAnalysis.Action("检查是否已覆盖索引，若已覆盖则回滚","","",List.of(citation));
        var checked=new AgentAnswerService.Validated(new AgentAnswerService.Answer(List.of(finding),List.of(action),List.of()),0,List.of());
        var proposals=new ObjectMapper().valueToTree(Map.of("findings",List.of(finding)));
        var incident=new IncidentSnapshot("s","case","ELK","","{\"alerts\":[]}","{\"logs\":[]}","now");
        var selection=new ObjectMapper().valueToTree(Map.of("findings",List.of(Map.of("index",0,"text",finding.text()))));
        var result=DiagnosticSourceRendering.render(checked,proposals,selection,Map.of(e.id(),e),incident,true).answer();
        assertThat(result.findings().get(0).text()).contains(source).doesNotContain("重试三次仍失败");
        assertThat(result.actions().get(0).text()).contains(source).doesNotContain("若已覆盖则回滚");
        assertThat(result.actions().get(0).command()).isEmpty();
    }
}
