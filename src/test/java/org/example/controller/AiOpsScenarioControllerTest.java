package org.example.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.agent.tool.QueryMetricsTools;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AiOpsScenarioControllerTest {
    @Test
    void allActiveScenariosGetChineseLabelsWithoutReplacingAlertIdentity() throws Exception {
        var mapper = new ObjectMapper();
        List<QueryMetricsTools.AlertScenario> scenarios;
        try (var input = new ClassPathResource("aiops-scenarios/alert_scenarios.json").getInputStream()) {
            scenarios = mapper.readValue(input, mapper.getTypeFactory().constructCollectionType(List.class, QueryMetricsTools.AlertScenario.class));
        }
        var tools = mock(QueryMetricsTools.class);
        when(tools.getScenarios()).thenReturn(scenarios);
        var summaries = new AiOpsScenarioController(tools).listScenarios();
        assertThat(summaries).hasSize(12);
        for (int i = 0; i < summaries.size(); i++) {
            assertThat(summaries.get(i).id()).isEqualTo(scenarios.get(i).getScenarioId());
            assertThat(summaries.get(i).alertName()).isEqualTo(scenarios.get(i).getAlert().getAlertName());
            assertThat(summaries.get(i).displayName()).containsPattern("[\\p{IsHan}]");
        }
        assertThat(summaries.get(0).displayName()).isEqualTo("Apdex SLO 违反（用户体验下降）");
    }
}
