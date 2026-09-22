package org.example.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.context.support.TestPropertySourceUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RerankServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"gte-rerank-v2", "qwen3.7-text-rerank"})
    void postsDashScopeShapeAndMapsRankedIndexesBackToCandidates(String model) throws Exception {
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        startServer(exchange -> {
            requestBody.set(objectMapper.readTree(exchange.getRequestBody()));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, """
                    {
                      "output": {
                        "results": [
                          {"index": 2, "relevance_score": 0.91},
                          {"index": 0, "relevance_score": 0.72}
                        ]
                      },
                      "request_id": "test-request"
                    }
                    """);
        });

        RerankService service = new RerankService(
                objectMapper,
                endpoint(),
                "test-key",
                model,
                Duration.ofSeconds(2));
        VectorSearchService.SearchResult first = candidate("first", "first document");
        VectorSearchService.SearchResult second = candidate("second", "second document");
        VectorSearchService.SearchResult third = candidate("third", "third document");

        List<VectorSearchService.SearchResult> ranked = service.rerank(
                "which document answers the question?",
                List.of(first, second, third),
                2);

        assertThat(ranked).containsExactly(third, first);
        assertThat(ranked).extracting(VectorSearchService.SearchResult::getRerankScore)
                .containsExactly(0.91, 0.72);
        assertThat(authorization.get()).isEqualTo("Bearer test-key");
        assertThat(requestBody.get().path("model").asText()).isEqualTo(model);
        assertThat(requestBody.get().path("input").path("query").asText())
                .isEqualTo("which document answers the question?");
        assertThat(requestBody.get().path("input").path("documents"))
                .extracting(JsonNode::asText)
                .containsExactly("first document", "second document", "third document");
        assertThat(requestBody.get().path("parameters").path("top_n").asInt()).isEqualTo(2);
        assertThat(requestBody.get().path("parameters").path("return_documents").asBoolean())
                .isFalse();
        assertThat(requestBody.get().path("parameters").has("instruct")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Prioritize documents that directly resolve the user's operational task."})
    void sendsConfiguredQwen37ModelAndOnlySendsConfiguredInstruction(String instruct) throws Exception {
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        startServer(exchange -> {
            requestBody.set(objectMapper.readTree(exchange.getRequestBody()));
            respond(exchange, 200, """
                    {"output":{"results":[{"index":0,"relevance_score":0.95}]},
                     "request_id":"test-default-model"}
                    """);
        });

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                    "dashscope.api.key=test-key",
                    "dashscope.rerank.endpoint=" + endpoint(),
                    "dashscope.rerank.model=qwen3.7-text-rerank",
                    "dashscope.rerank.instruct=" + instruct);
            context.registerBean(ObjectMapper.class, () -> objectMapper);
            context.register(RerankService.class);
            context.refresh();

            VectorSearchService.SearchResult candidate = candidate("one", "document");
            List<VectorSearchService.SearchResult> ranked = context.getBean(RerankService.class)
                    .rerank("question", List.of(candidate), 1);

            assertThat(requestBody.get().path("model").asText()).isEqualTo("qwen3.7-text-rerank");
            if (instruct.isEmpty()) {
                assertThat(requestBody.get().path("parameters").has("instruct")).isFalse();
            } else {
                assertThat(requestBody.get().path("parameters").path("instruct").asText())
                        .isEqualTo(instruct);
            }
            assertThat(ranked).containsExactly(candidate);
            assertThat(candidate.getRerankScore()).isEqualTo(0.95);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"gte-rerank-v2", "qwen3.7-text-rerank"})
    void propagatesRemoteFailureInsteadOfSilentlyReturningVectorOrder(String model) throws Exception {
        startServer(exchange -> respond(exchange, 500, "{\"code\":\"InternalError\",\"message\":\"failed\"}"));
        RerankService service = new RerankService(
                objectMapper,
                endpoint(),
                "test-key",
                model,
                Duration.ofSeconds(2));

        assertThatThrownBy(() -> service.rerank(
                "question",
                List.of(candidate("one", "document")),
                1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("500");
    }

    @Test
    void rejectsAnOversizedResponseInsteadOfExceedingTheRequestedLimit() throws Exception {
        startServer(exchange -> respond(exchange, 200, """
                {"output":{"results":[
                  {"index":0,"relevance_score":0.9},
                  {"index":1,"relevance_score":0.8}]}}
                """));
        RerankService service = new RerankService(
                objectMapper, endpoint(), "test-key", "qwen3.7-text-rerank", Duration.ofSeconds(2));

        assertThatThrownBy(() -> service.rerank("question",
                List.of(candidate("one", "document one"), candidate("two", "document two")), 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expected 1");
    }

    @Test
    void allowsApplicationStartupWithoutWorkspaceConfigurationUntilRerankIsUsed() {
        assertThatCode(() -> new RerankService(
                    objectMapper,
                    "",
                    "",
                    "test-key",
                    "gte-rerank-v2",
                    2_000))
                .doesNotThrowAnyException();
    }

    private void startServer(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rerank", exchange -> handler.handle(exchange));
        server.start();
    }

    private String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/rerank";
    }

    private VectorSearchService.SearchResult candidate(String id, String content) {
        VectorSearchService.SearchResult result = new VectorSearchService.SearchResult();
        result.setId(id);
        result.setContent(content);
        return result;
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
