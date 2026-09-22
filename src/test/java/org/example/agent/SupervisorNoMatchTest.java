package org.example.agent;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SupervisorNoMatchTest {
    @Test void emptySelectionDoesNotInventIncidents() {
        var agent = new SupervisorAgent(new SupervisorAgentTest().catalog(), null);
        var model = mock(DashScopeChatModel.class);
        when(model.call(anyString())).thenReturn("[]");
        assertThat(agent.identifyScenarios("天气怎么样", model)).isEmpty();
    }
    @Test void providerFailureIsNotReportedAsSuccessfulSelection() {
        var agent = new SupervisorAgent(new SupervisorAgentTest().catalog(), null);
        var model = mock(DashScopeChatModel.class);
        when(model.call(anyString())).thenThrow(new IllegalStateException("offline"));
        assertThatThrownBy(() -> agent.identifyScenarios("invalid page", model)).isInstanceOf(IllegalStateException.class);
    }
}
