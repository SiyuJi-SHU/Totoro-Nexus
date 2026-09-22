package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.example.controller.ConsoleObservabilityController;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ConsoleMetricsServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    @Test void emptyRegistryDoesNotPretendUncollectedValuesAreZero() {
        var registry = new SimpleMeterRegistry();
        try {
            var metrics = mapper.valueToTree(new ConsoleObservabilityController(registry, mock(ConsoleEvaluationService.class)).realtime());
            assertThat(metrics.path("apiCalls").isNull()).isTrue();
            assertThat(metrics.path("avgLatencyMs").isNull()).isTrue();
            assertThat(metrics.path("tokenUsage").isNull()).isTrue();
            assertThat(metrics.path("retrievalDocuments").isNull()).isTrue();
            assertThat(metrics.path("rerank").path("calls").isNull()).isTrue();
            assertThat(metrics.path("availability").path("http").asText()).isEqualTo("no_samples");
            assertThat(metrics.path("uptimeSeconds").asLong()).isGreaterThanOrEqualTo(0);
            assertThat(metrics.path("memory").path("heapUsedBytes").asLong()).isGreaterThan(0);
        } finally { registry.close(); }
    }
    @Test void measuresBusinessHttpAndRerankWhileExcludingConsolePollingAndActuator() {
        var registry = new SimpleMeterRegistry();
        try {
            timer(registry, "/api/ai_ops", "POST", "200", 100);
            timer(registry, "/api/ai_ops", "POST", "500", 300);
            timer(registry, "/api/console/metrics/realtime", "GET", "200", 10);
            timer(registry, "/actuator/health", "GET", "200", 10);
            Timer.builder("rag.rerank.duration").register(registry).record(Duration.ofMillis(50));
            Counter.builder("rag.rerank.fallback").register(registry).increment();
            Counter.builder("rag.rerank.timeout").register(registry);
            var metrics = mapper.valueToTree(new ConsoleObservabilityController(registry, mock(ConsoleEvaluationService.class)).realtime());
            assertThat(metrics.path("apiCalls").asInt()).isEqualTo(2);
            assertThat(metrics.path("http5xx").asInt()).isEqualTo(1);
            assertThat(metrics.path("avgLatencyMs").asDouble()).isEqualTo(200.0);
            assertThat(metrics.path("hotEndpoints")).hasSize(1);
            assertThat(metrics.path("hotEndpoints").get(0).path("calls").asInt()).isEqualTo(2);
            assertThat(metrics.path("rerank").path("calls").asInt()).isEqualTo(1);
            assertThat(metrics.path("rerank").path("avgLatencyMs").asDouble()).isEqualTo(50.0);
            assertThat(metrics.path("rerank").path("fallbacks").asDouble()).isEqualTo(1.0);
            assertThat(metrics.path("rerank").path("timeouts").asDouble()).isEqualTo(0.0);
        } finally { registry.close(); }
    }
    @Test void exposesModelTokenCountersWithModelBreakdown() {
        var registry = new SimpleMeterRegistry();
        try {
            Counter.builder("gen_ai.client.token.usage").tags("gen_ai.token.type", "input", "gen_ai.request.model", "qwen3-max").register(registry).increment(120);
            Counter.builder("gen_ai.client.token.usage").tags("gen_ai.token.type", "output", "gen_ai.request.model", "qwen3-max").register(registry).increment(80);
            Counter.builder("gen_ai.client.token.usage").tags("gen_ai.token.type", "total", "gen_ai.request.model", "qwen3-max").register(registry).increment(200);
            var metrics = mapper.valueToTree(new ConsoleObservabilityController(registry, mock(ConsoleEvaluationService.class)).realtime());
            assertThat(metrics.path("tokenUsage").path("inputTokens").asLong()).isEqualTo(120);
            assertThat(metrics.path("tokenUsage").path("outputTokens").asLong()).isEqualTo(80);
            assertThat(metrics.path("tokenUsage").path("totalTokens").asLong()).isEqualTo(200);
            assertThat(metrics.path("tokenUsage").path("models").get(0).path("model").asText()).isEqualTo("qwen3-max");
            assertThat(metrics.path("availability").path("tokenUsage").asText()).isEqualTo("measured");
        } finally { registry.close(); }
    }
    @Test void sumsInputAndOutputWhenProviderDoesNotEmitTotalAndSeparatesBusinessFailures() {
        var registry=new SimpleMeterRegistry();
        try {
            Counter.builder("gen_ai.client.token.usage").tags("gen_ai.token.type","input","gen_ai.request.model","qwen-plus").register(registry).increment(30);
            Counter.builder("gen_ai.client.token.usage").tags("gen_ai.token.type","output","gen_ai.request.model","qwen-plus").register(registry).increment(20);
            registry.counter("oncall.diagnosis.outcomes","mode","smart","status","no_match").increment();
            registry.timer("oncall.model.stage","stage","supervisor","outcome","success").record(Duration.ofMillis(150));
            var metrics=mapper.valueToTree(new ConsoleObservabilityController(registry,mock(ConsoleEvaluationService.class)).realtime());
            assertThat(metrics.path("tokenUsage").path("totalTokens").asLong()).isEqualTo(50);
            assertThat(metrics.path("businessOutcomes").get(0).path("status").asText()).isEqualTo("no_match");
            assertThat(metrics.path("modelStages").get(0).path("avgLatencyMs").asDouble()).isEqualTo(150);
        } finally { registry.close(); }
    }
    private void timer(SimpleMeterRegistry registry, String uri, String method, String status, long ms) {
        Timer.builder("http.server.requests").tags("uri",uri,"method",method,"status",status)
            .register(registry).record(Duration.ofMillis(ms));
    }
}
