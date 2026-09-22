package org.example.service;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import org.example.agent.SupervisorAgent;
import org.example.dto.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SmartAiOpsServiceTest {
    @Test void negatingAnAlternativeMustNotRejectTheRequestedDiagnosis() {
        var supervisor = mock(SupervisorAgent.class);
        var aiOps = mock(AiOpsService.class);
        var base = DiagnosisEvidenceTest.snapshot();
        var snapshot = new IncidentSnapshot(base.id(), base.scenarioId(), base.scenarioName(),
                "不要分析其他服务，只诊断本次PostgreSQL读取失败", base.alertsJson(), base.logsJson(), base.capturedAt());
        var outcome = new AiOpsService.DiagnosisOutcome(snapshot, "no_match", "无相关分析方向", "现场事实",
                GroundedAnalysis.insufficient(snapshot.service(), "无相关分析方向"), List.of());
        when(aiOps.insufficient(eq(snapshot), eq("no_match"), anyString())).thenReturn(outcome);
        when(supervisor.identifyIncidentDirections(any(IncidentSnapshot.class), any())).thenReturn(List.of());
        var service = new SmartAiOpsService(supervisor, aiOps, null);
        try {
            assertThat(service.diagnose(snapshot, null, null)).isSameAs(outcome);
            verify(supervisor).identifyIncidentDirections(any(IncidentSnapshot.class), any());
            verify(aiOps, never()).analyze(any(), anyString(), anyString(), any(), any(), any());
        } finally {
            service.close();
        }
    }

    @Test void noMatchAndInvalidScoresNeverStartWorkers() {
        var supervisor=mock(SupervisorAgent.class);var aiOps=mock(AiOpsService.class);var snapshot=DiagnosisEvidenceTest.snapshot();
        when(supervisor.identifyIncidentDirections(any(IncidentSnapshot.class),any())).thenReturn(List.of(new ScenarioMatch("s1","方向",.1,"low"),new ScenarioMatch("s2","方向",Double.NaN,"invalid")));
        var outcome=new AiOpsService.DiagnosisOutcome(snapshot,"no_match","无匹配","现场事实",GroundedAnalysis.insufficient(snapshot.service(),"无匹配"),List.of());
        when(aiOps.insufficient(eq(snapshot),eq("no_match"),anyString())).thenReturn(outcome);
        var service=new SmartAiOpsService(supervisor,aiOps,null);
        try {
            assertThat(service.diagnose(snapshot,null,null)).isSameAs(outcome);
            verify(aiOps,never()).analyze(any(),anyString(),anyString(),any(),any(),any());
            assertThat(SmartAiOpsService.result(outcome).getPrimary()).isNull();
        } finally {service.close();}
    }

    @Test void insufficientEvidenceDoesNotExposeAPlaceholderPrimaryDiagnosis() {
        var snapshot = DiagnosisEvidenceTest.snapshot();
        var outcome = new AiOpsService.DiagnosisOutcome(snapshot, "insufficient_evidence", "证据不足", "仅现场事实",
                GroundedAnalysis.insufficient(snapshot.service(), "证据不足"), List.of());
        var result = SmartAiOpsService.result(outcome);
        assertThat(result.getPrimary()).isNull();
        assertThat(result.isDegraded()).isTrue();
    }

    @Test void candidateWorkersOverlapButShareExactlyOneImmutableSnapshotAndSearchBudget() throws Exception {
        var supervisor=mock(SupervisorAgent.class);var aiOps=mock(AiOpsService.class);var snapshot=DiagnosisEvidenceTest.snapshot();
        var matches=List.of(new ScenarioMatch("s1","数据库",.9,"match"),new ScenarioMatch("s2","存储",.8,"match"),new ScenarioMatch("s3","内存",.7,"match"));
        when(supervisor.identifyIncidentDirections(any(IncidentSnapshot.class),any())).thenReturn(matches);
        var barrier=new CyclicBarrier(3);var budgets=ConcurrentHashMap.newKeySet();
        when(aiOps.analyze(same(snapshot),anyString(),anyString(),any(),any(),any())).thenAnswer(i->{
            budgets.add(i.getArgument(4));barrier.await(2,TimeUnit.SECONDS);
            return new AiOpsService.CandidateAnalysis(i.getArgument(1),i.getArgument(2),DiagnosisEvidenceTest.analysis(),List.of(DiagnosisEvidenceTest.doc()),List.of());
        });
        var outcome=new AiOpsService.DiagnosisOutcome(snapshot,"completed","完成","统一报告",DiagnosisEvidenceTest.analysis(),List.of(DiagnosisEvidenceTest.doc()));
        when(aiOps.summarize(same(snapshot),anyList(),any(),any())).thenReturn(outcome);
        var service=new SmartAiOpsService(supervisor,aiOps,null);
        try {
            assertThat(service.diagnose(snapshot,mock(DashScopeChatModel.class),null)).isSameAs(outcome);
            assertThat(budgets).hasSize(1);
            verify(aiOps,times(3)).analyze(same(snapshot),anyString(),anyString(),any(),any(),any());
            verify(aiOps,times(1)).summarize(same(snapshot),argThat(c->c.size()==3),any(),any());
            verify(aiOps,never()).capture(any(),any(),any());
        }finally {service.close();}
    }
    @Test void deadlineCancelsOutstandingWorkInsteadOfReturningSuccess() throws Exception {
        var supervisor=mock(SupervisorAgent.class);var aiOps=mock(AiOpsService.class);
        var interrupted=new CountDownLatch(1);var started=new CountDownLatch(1);
        when(supervisor.identifyIncidentDirections(any(IncidentSnapshot.class),any())).thenAnswer(i->{
            started.countDown();try {Thread.sleep(5000);}catch(InterruptedException e){interrupted.countDown();throw e;}return List.of();
        });
        var service=new SmartAiOpsService(supervisor,aiOps,null);ReflectionTestUtils.setField(service,"timeoutMs",300L);
        try {
            assertThatThrownBy(()->service.diagnose(DiagnosisEvidenceTest.snapshot(),null,null)).hasMessageContaining("时间上限");
            assertThat(started.getCount()).isZero();assertThat(interrupted.await(2,TimeUnit.SECONDS)).isTrue();
            verifyNoInteractions(aiOps);
        }finally{service.close();}
    }
}
