package org.example.dto;

import lombok.*;

@Data @NoArgsConstructor @AllArgsConstructor
public class DiagnosisReport {
    private String scenarioId;
    private String scenarioName;
    private double initialConfidence;
    private String report;
}
