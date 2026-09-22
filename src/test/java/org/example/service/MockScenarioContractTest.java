package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.agent.tool.QueryMetricsTools;
import org.example.agent.tool.QueryLogsTools;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Instant;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

class MockScenarioContractTest {
    @Test
    void everySnapshotHasConsistentTimesAndPassesObservationsWithoutAnswerKeys() throws Exception {
        var mapper = new ObjectMapper();
        var resource = new ClassPathResource("aiops-scenarios/alert_scenarios_mock.json");
        var metrics = new QueryMetricsTools();
        ReflectionTestUtils.setField(metrics, "mockEnabled", true);
        ReflectionTestUtils.setField(metrics, "timeout", 10);
        ReflectionTestUtils.setField(metrics, "scenarioResource", resource);
        metrics.init();
        var logs = new QueryLogsTools();
        ReflectionTestUtils.setField(logs, "mockEnabled", true);
        ReflectionTestUtils.setField(logs, "queryMetricsTools", metrics);
        try (var input = resource.getInputStream()) {
            var scenarios = mapper.readTree(input);
            assertThat(scenarios.size()).isEqualTo(12);
            for (var scenario : scenarios) {
                assertThat(scenario.has("expected_root_cause")).isFalse();
                assertThat(scenario.has("expected_actions")).isFalse();
                assertThat(scenario.has("expected_docs")).isFalse();
                metrics.setCurrentScenario(scenario.path("scenario_id").asText());
                var alert = mapper.readTree(metrics.queryPrometheusAlerts()).path("alerts").get(0);
                var snapshot = new org.example.dto.IncidentSnapshot("test", scenario.path("scenario_id").asText(), "测试", "",
                        metrics.queryPrometheusAlerts(), logs.queryLogs("ap-guangzhou", "application-logs", "*", 20), Instant.now().toString());
                var rendered = new DiagnosticReportService().render(snapshot,
                        org.example.dto.GroundedAnalysis.insufficient(snapshot.service(), "证据不足"), java.util.List.of());
                assertThat(rendered).contains("## 现场概况", "模拟现场快照", "证据不足", alert.path("active_at").asText(), alert.path("observed_at").asText());
                for (String field : new String[]{"severity", "service", "duration", "current_value", "threshold", "active_at", "observed_at", "instance", "environment", "impact", "data_source"}) {
                    assertThat(alert.path(field).asText()).as(field).isNotBlank();
                    assertThat(alert.path(field).asText()).isEqualTo(scenario.path("alert").path(field).asText());
                }
                var start = Instant.parse(alert.path("active_at").asText());
                var end = Instant.parse(alert.path("observed_at").asText());
                assertThat(Duration.between(start, end)).isEqualTo(Duration.parse("PT" + alert.path("duration").asText().toUpperCase()));
                var output = logs.queryLogs("ap-guangzhou", "application-logs", "*", 20);
                assertThat(output).doesNotContain("expected_root_cause", "expected_actions", "expected_docs");
                var entries = mapper.readTree(output).path("logs");
                assertThat(entries.size()).isGreaterThanOrEqualTo(3);
                for (var log : entries) {
                    assertThat(Instant.parse(log.path("timestamp").asText())).isBetween(start, end);
                    assertThat(log.path("instance").asText()).isEqualTo(alert.path("instance").asText());
                    assertThat(log.path("service").asText()).isEqualTo(alert.path("service").asText());
                }
                // Even if legacy evaluation keys are present, neither tool may expose them.
                var loaded = metrics.getScenarios().stream().filter(s -> s.getScenarioId().equals(scenario.path("scenario_id").asText())).findFirst().orElseThrow();
                loaded.setExpectedRootCause("ANSWER_KEY_SENTINEL");
                loaded.setExpectedActions(java.util.List.of("ANSWER_KEY_SENTINEL"));
                assertThat(metrics.queryPrometheusAlerts() + logs.queryLogs("ap-guangzhou", "application-logs", "*", 20)).doesNotContain("ANSWER_KEY_SENTINEL");
            }
        }
    }
}
