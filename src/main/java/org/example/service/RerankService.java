package org.example.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Calls the DashScope text-rerank API and maps ranked indexes to Milvus candidates. */
@Service
public class RerankService {

    private static final String RERANK_PATH =
            "/api/v1/services/rerank/text-rerank/text-rerank";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String configuredEndpoint;
    private final String workspaceId;
    private final String apiKey;
    private final String model;
    private final String instruct;
    private final Duration timeout;
    @Autowired(required=false) private org.example.platform.UsageLedger ledger;

    @Autowired
    public RerankService(
            ObjectMapper objectMapper,
            @Value("${dashscope.rerank.endpoint:}") String configuredEndpoint,
            @Value("${dashscope.rerank.workspace-id:}") String workspaceId,
            @Value("${dashscope.api.key}") String apiKey,
            @Value("${dashscope.rerank.model:gte-rerank-v2}") String model,
            @Value("${dashscope.rerank.instruct:}") String instruct,
            @Value("${dashscope.rerank.timeout-ms:30000}") long timeoutMs) {
        this(
                objectMapper,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofMillis(timeoutMs))
                        .build(),
                configuredEndpoint,
                workspaceId,
                apiKey,
                model,
                instruct,
                Duration.ofMillis(timeoutMs));
    }

    RerankService(
            ObjectMapper objectMapper,
            String endpoint,
            String apiKey,
            String model,
            Duration timeout) {
        this(
                objectMapper,
                HttpClient.newBuilder().connectTimeout(timeout).build(),
                endpoint,
                "",
                apiKey,
                model,
                "",
                timeout);
    }

    RerankService(
            ObjectMapper objectMapper,
            String endpoint,
            String workspaceId,
            String apiKey,
            String model,
            long timeoutMs) {
        this(
                objectMapper,
                HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMs)).build(),
                endpoint,
                workspaceId,
                apiKey,
                model,
                "",
                Duration.ofMillis(timeoutMs));
    }

    RerankService(
            ObjectMapper objectMapper,
            HttpClient httpClient,
            String configuredEndpoint,
            String workspaceId,
            String apiKey,
            String model,
            String instruct,
            Duration timeout) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.configuredEndpoint = configuredEndpoint;
        this.workspaceId = workspaceId;
        this.apiKey = requireText(apiKey, "DashScope API key");
        this.model = requireText(model, "rerank model");
        this.instruct = instruct == null ? "" : instruct.trim();
        this.timeout = timeout;
    }

    public List<VectorSearchService.SearchResult> rerank(
            String query,
            List<VectorSearchService.SearchResult> candidates,
            int topN) {
        requireText(query, "rerank query");
        if (candidates == null) {
            throw new IllegalArgumentException("rerank candidates must not be null");
        }
        if (topN < 1) {
            throw new IllegalArgumentException("rerank topN must be at least 1");
        }
        if (candidates.isEmpty()) {
            return List.of();
        }

        int requested = Math.min(topN, candidates.size());
        long start=System.nanoTime();String outcome="failed";Integer tokens=null;
        try {
            String requestJson = objectMapper.writeValueAsString(buildRequest(query, candidates, requested));
            URI endpoint = URI.create(resolveEndpoint(configuredEndpoint, workspaceId));
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException(
                        "Rerank API returned HTTP " + response.statusCode() + ": " + response.body());
            }
            var usage=objectMapper.readTree(response.body()).path("usage");
            if(usage.has("total_tokens"))tokens=usage.path("total_tokens").intValue();
            var mapped=mapResults(response.body(), candidates, requested);outcome="success";return mapped;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            if(e instanceof InterruptedException)Thread.currentThread().interrupt();
            throw new IllegalStateException("Rerank API call failed: " + e.getMessage(), e);
        } finally {if(ledger!=null)ledger.record("rerank","rerank",model,null,null,tokens,start,Thread.currentThread().isInterrupted()?"cancelled":outcome);}
    }

    private Map<String, Object> buildRequest(
            String query,
            List<VectorSearchService.SearchResult> candidates,
            int topN) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", model);
        request.put("input", Map.of(
                "query", query,
                "documents", candidates.stream()
                        .map(VectorSearchService.SearchResult::getContent)
                        .toList()));
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("return_documents", false);
        parameters.put("top_n", topN);
        if (!instruct.isBlank() && "qwen3.7-text-rerank".equals(model)) {
            parameters.put("instruct", instruct);
        }
        request.put("parameters", parameters);
        return request;
    }

    private List<VectorSearchService.SearchResult> mapResults(
            String responseBody,
            List<VectorSearchService.SearchResult> candidates,
            int expectedCount) throws Exception {
        JsonNode results = objectMapper.readTree(responseBody).path("output").path("results");
        if (!results.isArray()) {
            throw new IllegalStateException("Rerank API response is missing output.results");
        }

        boolean[] seen = new boolean[candidates.size()];
        List<VectorSearchService.SearchResult> ranked = new ArrayList<>();
        for (JsonNode resultNode : results) {
            int index = resultNode.path("index").asInt(-1);
            double relevanceScore = resultNode.path("relevance_score").asDouble(Double.NaN);
            if (index < 0 || index >= candidates.size() || seen[index]) {
                throw new IllegalStateException("Rerank API returned an invalid candidate index: " + index);
            }
            if (!Double.isFinite(relevanceScore) || relevanceScore < 0 || relevanceScore > 1) {
                throw new IllegalStateException("Rerank API returned an invalid relevance score");
            }
            seen[index] = true;
            VectorSearchService.SearchResult candidate = candidates.get(index);
            candidate.setRerankScore(relevanceScore);
            ranked.add(candidate);
        }
        if (ranked.size() != expectedCount) {
            throw new IllegalStateException(
                    "Rerank API returned " + ranked.size() + " results; expected " + expectedCount);
        }
        return ranked;
    }

    private static String resolveEndpoint(String configuredEndpoint, String workspaceId) {
        if (configuredEndpoint != null && !configuredEndpoint.isBlank()) {
            return configuredEndpoint.trim();
        }
        String normalizedWorkspaceId = requireText(workspaceId, "DashScope workspace ID");
        if (!normalizedWorkspaceId.matches("[A-Za-z0-9-]+")) {
            throw new IllegalArgumentException("DashScope workspace ID contains invalid characters");
        }
        return "https://" + normalizedWorkspaceId + ".cn-beijing.maas.aliyuncs.com" + RERANK_PATH;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
