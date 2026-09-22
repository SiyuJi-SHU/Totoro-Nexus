package org.example.dto;

import lombok.*;

/** Confidence represents model-estimated scenario relevance, never a verified cause. */
@Data @NoArgsConstructor @AllArgsConstructor
public class ScenarioMatch {
    private String scenarioId;
    private String scenarioName;
    private double confidence;
    private String reason;
}
