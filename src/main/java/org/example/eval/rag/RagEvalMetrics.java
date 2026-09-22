package org.example.eval.rag;

import java.util.ArrayList;
import java.util.List;

public final class RagEvalMetrics {

    private RagEvalMetrics() {
    }

    public static RagEvalSummary aggregate(List<RagEvalResult> results) {
        RagEvalSummary summary = new RagEvalSummary();
        if (results == null || results.isEmpty()) {
            return summary;
        }

        int total = results.size();
        int recallAt1Count = 0;
        int recallAtKCount = 0;
        double reciprocalRankSum = 0.0;
        double keywordRateSum = 0.0;
        double latencySum = 0.0;
        List<String> failedCaseIds = new ArrayList<>();

        for (RagEvalResult result : results) {
            if (result.isRecallAt1()) {
                recallAt1Count++;
            }
            if (result.isRecallAtK()) {
                recallAtKCount++;
                reciprocalRankSum += 1.0 / result.getHitRank();
            } else {
                failedCaseIds.add(result.getId());
            }
            keywordRateSum += result.getKeywordHitRate();
            latencySum += result.getLatencyMs();
        }

        summary.setTotalCases(total);
        summary.setRecallAt1(recallAt1Count * 1.0 / total);
        summary.setRecallAtK(recallAtKCount * 1.0 / total);
        summary.setMrr(reciprocalRankSum / total);
        summary.setAverageKeywordHitRate(keywordRateSum / total);
        summary.setAverageLatencyMs(latencySum / total);
        summary.setFailedCaseIds(failedCaseIds);
        return summary;
    }
}
