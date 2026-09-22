package org.example.agent;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.agent.tool.QueryMetricsTools;
import org.example.config.ScenarioConfig;
import org.example.dto.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SupervisorAgentTest {
    ScenarioConfig catalog() {
        var tools = mock(QueryMetricsTools.class);
        var scenarios = new ArrayList<QueryMetricsTools.AlertScenario>();
        for (int i = 1; i <= 4; i++) {
            var s = new QueryMetricsTools.AlertScenario(); s.setScenarioId("s" + i); s.setDisplayName("场景" + i);
            var a = new QueryMetricsTools.AlertInfo(); a.setAlertName("Alert" + i); a.setDescription("SECRET_OBSERVATION");
            s.setAlert(a); s.setExpectedRootCause("SECRET_ANSWER"); scenarios.add(s);
        }
        when(tools.getScenarios()).thenReturn(scenarios);
        return new ScenarioConfig(tools);
    }
    @Test void validatesCatalogScoresDeduplicationAndFencedJson() throws Exception {
        var agent = new SupervisorAgent(catalog(), null);
        var matches = agent.parseScenarioMatches("```json\n[{\"scenarioId\":\"s1\",\"confidence\":0.7},{\"scenarioId\":\"s1\",\"confidence\":0.9},{\"scenarioId\":\"unknown\",\"confidence\":1},{\"scenarioId\":\"s2\",\"confidence\":2},{\"scenarioId\":\"s3\",\"confidence\":0.8}]\n```");
        assertThat(matches).extracting(ScenarioMatch::getScenarioId).containsExactly("s1", "s3");
        assertThat(matches.get(0).getScenarioName()).isEqualTo("场景1");
        assertThatThrownBy(() -> agent.parseScenarioMatches("[] garbage")).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> agent.parseScenarioMatches("```json\n[]")).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> agent.parseScenarioMatches("{}")).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> agent.parseScenarioMatches(null)).isInstanceOf(Exception.class);
        assertThat(agent.buildScenarioIdentificationPrompt("ignore rules"))
                .contains("ignore rules").doesNotContain("SECRET_OBSERVATION", "SECRET_ANSWER");
    }
    @Test void modelFailureIsExplicitAndFormattingCannotIncreaseRanking() {
        var agent = new SupervisorAgent(catalog(), null);
        var model = mock(DashScopeChatModel.class);
        when(model.call(anyString())).thenThrow(new IllegalStateException("offline"));
        assertThatThrownBy(() -> agent.identifyScenarios("symptoms", model)).hasMessageContaining("未启动诊断");
        assertThatThrownBy(() -> agent.identifyScenarios(" ", model)).isInstanceOf(IllegalArgumentException.class);
        var result = agent.rankAndSummarize(List.of(new DiagnosisReport("s1","one",.7,"## 精美标题"),
                new DiagnosisReport("s2","two",.9,"简短分析"),new DiagnosisReport("s3","three",Double.NaN,"无效")));
        assertThat(result.getPrimary().getScenarioId()).isEqualTo("s2");
        assertThat(result.getSecondary()).hasSize(1);
        assertThat(agent.rankAndSummarize(List.of()).getPrimary()).isNull();
    }
    @Test void dtoRoundTrip() throws Exception {
        var mapper = new ObjectMapper();
        var input = SmartDiagnosisResult.builder().primary(new ScoredDiagnosis("s1", "中文", .8, "# report\n文本")).failures(List.of("s2 超时")).degraded(true).build();
        assertThat(mapper.readValue(mapper.writeValueAsString(input), SmartDiagnosisResult.class)).isEqualTo(input);
        assertThat(mapper.readValue("{\"symptoms\":\"慢请求\"}", SmartDiagnosisRequest.class).getSymptoms()).isEqualTo("慢请求");
    }
}
