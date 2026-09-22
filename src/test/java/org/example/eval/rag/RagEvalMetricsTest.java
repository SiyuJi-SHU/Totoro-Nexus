package org.example.eval.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RagEvalMetricsTest {

    @Test
    void aggregatesRecallMrrKeywordRateAndLatency() {
        RagEvalResult first = new RagEvalResult();
        first.setId("rag_cpu_001");
        first.setHitRank(1);
        first.setKeywordHitRate(0.5);
        first.setLatencyMs(100);

        RagEvalResult second = new RagEvalResult();
        second.setId("rag_memory_001");
        second.setHitRank(3);
        second.setKeywordHitRate(1.0);
        second.setLatencyMs(300);

        RagEvalResult missed = new RagEvalResult();
        missed.setId("rag_disk_001");
        missed.setHitRank(-1);
        missed.setKeywordHitRate(0.0);
        missed.setLatencyMs(200);

        RagEvalSummary summary = RagEvalMetrics.aggregate(List.of(first, second, missed));

        assertThat(summary.getTotalCases()).isEqualTo(3);
        assertThat(summary.getRecallAt1()).isEqualTo(1.0 / 3.0);
        assertThat(summary.getRecallAtK()).isEqualTo(2.0 / 3.0);
        assertThat(summary.getMrr()).isEqualTo((1.0 + 1.0 / 3.0) / 3.0);
        assertThat(summary.getAverageKeywordHitRate()).isEqualTo(0.5);
        assertThat(summary.getAverageLatencyMs()).isEqualTo(200.0);
        assertThat(summary.getFailedCaseIds()).containsExactly("rag_disk_001");
    }
}
