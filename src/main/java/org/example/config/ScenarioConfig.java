package org.example.config;

import org.example.agent.tool.QueryMetricsTools;
import org.springframework.stereotype.Component;
import java.util.*;

/** Classifier catalog: only identities and human-readable names, never fixture evidence/answers. */
@Component
public class ScenarioConfig {
    private final QueryMetricsTools metrics;
    public ScenarioConfig(QueryMetricsTools metrics) { this.metrics = metrics; }

    public List<ScenarioMetadata> getScenarios() {
        Map<String, ScenarioMetadata> catalog = new LinkedHashMap<>();
        for (var scenario : metrics.getScenarios()) {
            if (scenario.getScenarioId() == null || scenario.getAlert() == null) continue;
            String alertName = scenario.getAlert().getAlertName();
            String name = scenario.getDisplayName();
            if (name == null || name.isBlank()) name = alertName;
            // Fixture descriptions include observed metric values; do not send those to classification.
            catalog.putIfAbsent(scenario.getScenarioId(),
                    new ScenarioMetadata(scenario.getScenarioId(), name, alertName));
        }
        return List.copyOf(catalog.values());
    }

    public record ScenarioMetadata(String scenarioId, String scenarioName, String description) {}
}
