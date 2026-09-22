package org.example.dto;

import lombok.*;

@Data @NoArgsConstructor @AllArgsConstructor
public class SmartDiagnosisRequest {
    private String symptoms;
    private String scenarioId;
    private String sessionId;
    public SmartDiagnosisRequest(String symptoms) { this.symptoms = symptoms; }
}
