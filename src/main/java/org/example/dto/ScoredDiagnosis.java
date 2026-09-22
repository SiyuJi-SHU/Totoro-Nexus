package org.example.dto;

import lombok.*;

/** Score is a report-ranking heuristic, not a probability of the root cause. */
@Data @NoArgsConstructor @AllArgsConstructor
public class ScoredDiagnosis {
    private String scenarioId;
    private String scenarioName;
    private double score;
    private String report;
}
