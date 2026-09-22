package org.example.eval.rag;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class RagEvalSummary {
    private int totalCases;
    private double recallAt1;
    private double recallAtK;
    private double mrr;
    private double averageKeywordHitRate;
    private double averageLatencyMs;
    private List<String> failedCaseIds = new ArrayList<>();
}
