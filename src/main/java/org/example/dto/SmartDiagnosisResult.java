package org.example.dto;

import lombok.*;
import java.util.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SmartDiagnosisResult {
    private String diagnosisId;
    private String status;
    private String message;
    private String report;
    @Builder.Default private List<EvidenceDocument> evidenceDocuments = new ArrayList<>();
    private ScoredDiagnosis primary;
    @Builder.Default private List<ScoredDiagnosis> secondary = new ArrayList<>();
    @Builder.Default private List<String> failures = new ArrayList<>();
    private boolean degraded;
}
