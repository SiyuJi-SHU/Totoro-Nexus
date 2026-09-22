package org.example.controller;

import org.example.agent.tool.QueryMetricsTools;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.io.IOException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

@RestController
@RequestMapping("/api/scenarios")
public class AiOpsScenarioController {

    private final QueryMetricsTools queryMetricsTools;
    private final Map<String, String> displayNames = new HashMap<>();

    public AiOpsScenarioController(QueryMetricsTools queryMetricsTools) throws IOException {
        this.queryMetricsTools = queryMetricsTools;
        // Read labels only: the active scenario evidence and alert identifiers stay authoritative.
        try (var input = new ClassPathResource("aiops-scenarios/alert_scenarios_cn.json").getInputStream()) {
            for (var node : new ObjectMapper().readTree(input)) {
                displayNames.put(node.path("scenario_id").asText(), node.path("display_name").asText());
            }
        }
    }

    @GetMapping("/list")
    public List<ScenarioSummary> listScenarios() {
        return queryMetricsTools.getScenarios().stream()
                .map(scenario -> new ScenarioSummary(
                        scenario.getScenarioId(),
                        scenario.getAlert().getAlertName(),
                        scenario.getDisplayName() != null && !scenario.getDisplayName().isBlank()
                                ? scenario.getDisplayName() : displayNames.get(scenario.getScenarioId()),
                        scenario.getAlert().getDescription()))
                .toList();
    }

    @PostMapping("/select")
    public void selectScenario(@RequestParam String scenarioId) {
        try {
            queryMetricsTools.setCurrentScenario(scenarioId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    public record ScenarioSummary(String id, String alertName, String displayName, String description) {
    }
}
