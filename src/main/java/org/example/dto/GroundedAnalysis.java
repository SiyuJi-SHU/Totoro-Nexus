package org.example.dto;

import java.util.List;

/** Model output is checked before any fields enter the user-facing report. */
public record GroundedAnalysis(boolean relevant, String service, List<Finding> findings,
        List<Finding> contradictions, List<Action> actions, List<String> missingEvidence) {
    public record Citation(String id, String quote) {}
    public record Finding(String text, String certainty, List<Citation> citations) {}
    public record Action(String text, String command, String prerequisites, List<Citation> citations) {}
    public static GroundedAnalysis insufficient(String service, String reason) {
        return new GroundedAnalysis(false, service, List.of(), List.of(), List.of(), List.of(reason));
    }
}
