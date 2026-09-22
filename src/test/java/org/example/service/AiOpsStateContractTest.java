package org.example.service;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.graph.OverAllState;
import org.example.dto.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AiOpsStateContractTest {
    private AiOpsService service(KnowledgeRetrievalService retrieval,ChatModelFactory models) {
        var service=new AiOpsService();
        ReflectionTestUtils.setField(service,"retrieval",retrieval);
        ReflectionTestUtils.setField(service,"models",models);
        ReflectionTestUtils.setField(service,"reports",new DiagnosticReportService());
        return service;
    }
    @Test void noEvidenceReturnsOnlyFactsAndDoesNotCallGeneration() {
        var retrieval=mock(KnowledgeRetrievalService.class);var models=mock(ChatModelFactory.class);
        when(retrieval.search(anyString(),any(),anyInt())).thenReturn(new KnowledgeRetrievalService.Bundle("",2,"no_results",List.of(),List.of(),1));
        var outcome=service(retrieval,models).diagnose(DiagnosisEvidenceTest.snapshot(),mock(DashScopeChatModel.class),s->{});
        assertThat(outcome.status()).isEqualTo("insufficient_evidence");
        assertThat(outcome.report()).contains("16m","当前证据不足").doesNotContain("SHOW data_checksums;");
        verifyNoInteractions(models);
    }
    @Test void semanticIrrelevanceAdvancesToKeywordStageAndOneFinalAudit() {
        var retrieval=mock(KnowledgeRetrievalService.class);var models=mock(ChatModelFactory.class);var model=mock(DashScopeChatModel.class);
        var reports=new DiagnosticReportService();var incident=DiagnosisEvidenceTest.snapshot();var doc=DiagnosisEvidenceTest.doc();
        var other=KeywordSearchService.document("other.md","other",0,"Unrelated network configuration","vector",.5,null,null);
        when(retrieval.search(anyString(),any(),eq(0))).thenReturn(new KnowledgeRetrievalService.Bundle("",0,"candidates",List.of(other),List.of(),1));
        when(retrieval.search(anyString(),any(),eq(1))).thenReturn(new KnowledgeRetrievalService.Bundle("",1,"candidates",List.of(doc),List.of(),1));
        when(models.call(eq(model),eq("worker-analysis"),anyString(),anyString())).thenReturn(
                reports.encode(GroundedAnalysis.insufficient(incident.service(),"无关")),reports.encode(DiagnosisEvidenceTest.analysis()));
        when(models.call(eq(model),eq("report-audit"),anyString(),anyString())).thenReturn("{\"findings\":[0],\"actions\":[0]}");
        var outcome=service(retrieval,models).diagnose(incident,model,s->{});
        assertThat(outcome.status()).isEqualTo("completed");assertThat(outcome.documents()).containsExactly(doc);
        verify(models,times(2)).call(eq(model),eq("worker-analysis"),anyString(),anyString());
        verify(models,times(1)).call(eq(model),eq("report-audit"),anyString(),anyString());
        verify(retrieval,never()).search(anyString(),any(),eq(2));
    }
    @Test void invalidWorkerCitationsGoToFinalAuditInsteadOfRepeatingRetrieval() {
        var retrieval=mock(KnowledgeRetrievalService.class);var models=mock(ChatModelFactory.class);var model=mock(DashScopeChatModel.class);
        var reports=new DiagnosticReportService();var doc=DiagnosisEvidenceTest.doc();
        when(retrieval.search(anyString(),any(),eq(0))).thenReturn(
                new KnowledgeRetrievalService.Bundle("",0,"candidates",List.of(doc),List.of(),1));
        when(retrieval.search(anyString(),any(),eq(1))).thenReturn(
                new KnowledgeRetrievalService.Bundle("",2,"no_results",List.of(),List.of(),1));
        // Real failure: the Worker identifies the correct document but omits the log citation.
        var docOnly=new GroundedAnalysis(true,"postgres-main",List.of(new GroundedAnalysis.Finding(
                "数据页读取失败","observation",List.of(new GroundedAnalysis.Citation(doc.id(),
                "Page read failures can indicate data corruption.")))),List.of(),List.of(),List.of());
        when(models.call(eq(model),eq("worker-analysis"),anyString(),anyString())).thenReturn(reports.encode(docOnly));
        when(models.call(eq(model),eq("report-audit"),anyString(),anyString())).thenReturn(reports.encode(DiagnosisEvidenceTest.analysis()));
        var outcome=service(retrieval,models).diagnose(DiagnosisEvidenceTest.snapshot(),model,s->{});
        assertThat(outcome.status()).isEqualTo("completed");
        verify(retrieval,times(1)).search(anyString(),any(),anyInt());
        verify(models,times(1)).call(eq(model),eq("worker-analysis"),anyString(),anyString());
        verify(models,times(1)).call(eq(model),eq("report-audit"),anyString(),anyString());
    }
    @Test void finalAuditSelectsValidatedItemsWithoutRewritingFactsOrCommands() {
        var retrieval=mock(KnowledgeRetrievalService.class);var models=mock(ChatModelFactory.class);var model=mock(DashScopeChatModel.class);
        var reports=new DiagnosticReportService();var doc=DiagnosisEvidenceTest.doc();var original=DiagnosisEvidenceTest.analysis();
        when(retrieval.search(anyString(),any(),eq(0))).thenReturn(new KnowledgeRetrievalService.Bundle("",0,"candidates",List.of(doc),List.of(),1));
        when(models.call(eq(model),eq("worker-analysis"),anyString(),anyString())).thenReturn(reports.encode(original));
        when(models.call(eq(model),eq("report-audit"),anyString(),anyString())).thenReturn(
                "{\"findings\":[0],\"contradictions\":[],\"actions\":[0],\"missingEvidence\":[\"磁盘健康状态\"]}");
        var outcome=service(retrieval,models).diagnose(DiagnosisEvidenceTest.snapshot(),model,s->{});
        assertThat(outcome.status()).isEqualTo("completed");
        assertThat(outcome.analysis().findings()).containsExactlyElementsOf(original.findings());
        assertThat(outcome.analysis().actions()).containsExactlyElementsOf(original.actions());
        verify(models,times(1)).call(eq(model),eq("report-audit"),anyString(),anyString());
    }
    @Test void noAlertDoesNotStartRetrievalOrModelCalls() {
        var retrieval=mock(KnowledgeRetrievalService.class);var models=mock(ChatModelFactory.class);
        var empty=new IncidentSnapshot("empty","s1","empty","查询告警","{\"alerts\":[]}","{\"logs\":[]}","now");
        assertThat(service(retrieval,models).diagnose(empty,null,s->{}).status()).isEqualTo("no_alerts");
        verifyNoInteractions(retrieval,models);
    }
    @Test void keepsLegacyPlannerOutputForStoredReports() {
        String report="# 告警分析报告\n历史报告";
        assertThat(new AiOpsService().extractFinalReport(new OverAllState(Map.of("planner_plan",new AssistantMessage(report))))).contains(report);
    }
}
