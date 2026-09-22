package org.example.service;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.example.platform.UsageLedger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatModelSelectionTest {
    @Test void selectionsAreIsolatedAndPreserveUsageAccounting() {
        var factory=new ChatModelFactory("test-key","deepseek-v4-flash",ObservationRegistry.NOOP,new SimpleMeterRegistry());
        var ledger=mock(UsageLedger.class);ReflectionTestUtils.setField(factory,"ledger",ledger);
        assertThat(factory.forModel(null)).isSameAs(factory);
        assertThat(factory.forModel("deepseek-v4-flash")).isSameAs(factory);
        var pro=CompletableFuture.supplyAsync(()->factory.forModel("deepseek-v4-pro")).join();
        var flash=CompletableFuture.supplyAsync(()->factory.forModel("deepseek-v4-flash")).join();
        assertThat(factory.modelName()).isEqualTo("deepseek-v4-flash");
        assertThat(pro.modelName()).isEqualTo("deepseek-v4-pro");
        assertThat(flash.modelName()).isEqualTo("deepseek-v4-flash");
        var options=(DashScopeChatOptions)pro.create(.1,400,.9).getDefaultOptions();
        assertThat(options.getModel()).isEqualTo("deepseek-v4-pro");
        assertThat(options.getEnableThinking()).isFalse();
        var client=mock(com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel.class);
        when(client.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("ok")))));
        pro.response(client,"task-routing",new Prompt("test"));
        verify(ledger).record(eq("task-routing"),eq("chat"),eq("deepseek-v4-pro"),any(),any(),any(),anyLong(),eq("success"),anyMap());
        assertThatThrownBy(()->factory.forModel("unverified-model")).hasMessageContaining("文本 LLM");
    }
}
