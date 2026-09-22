package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.example.agent.tool.InternalDocsTools;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.http.HttpTimeoutException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RetrievalTraceTest {
    private final VectorSearchService search = mock(VectorSearchService.class);
    private final RerankService rerank = mock(RerankService.class);

    private RetrievalPipelineService pipeline(boolean enabled) {
        var service = new RetrievalPipelineService(search, rerank, new SimpleMeterRegistry(), enabled, 10);
        ReflectionTestUtils.setField(service, "embeddingModel", "test-embedding");
        ReflectionTestUtils.setField(service, "rerankModel", "test-reranker");
        return service;
    }

    private VectorSearchService.SearchResult document(String file, float score, Double rerankScore) {
        var result = new VectorSearchService.SearchResult();
        result.setSourceFile(file);
        result.setContent("do not expose full content in telemetry");
        result.setScore(score);
        result.setRerankScore(rerankScore);
        result.setChunkIndex(2);
        return result;
    }

    @Test
    void exposesActualCandidateCountSeparateScoresAndCompactRankedResults() throws Exception {
        var first = document("one.md", .7f, .91);
        var second = document("two.md", .8f, .84);
        var third = document("three.md", .6f, .73);
        var fourth = document("four.md", .5f, .42);
        var candidates = List.of(second, first, third, fourth);
        when(search.searchSimilarDocuments("question", 10)).thenAnswer(invocation -> {
            Thread.sleep(12);
            return candidates;
        });
        when(rerank.rerank("question", candidates, 4)).thenAnswer(invocation -> {
            Thread.sleep(12);
            return List.of(first, second, third, fourth);
        });
        var service = pipeline(true);
        var result = service.retrieve("question", 4);
        var trace = service.trace("question", "alert-runbook", result);
        var json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(trace));
        assertThat(json.path("candidateCount").asInt()).isEqualTo(4);
        assertThat(json.path("resultCount").asInt()).isEqualTo(4);
        assertThat(json.path("embeddingModel").asText()).isEqualTo("test-embedding");
        assertThat(json.path("rerankModel").asText()).isEqualTo("test-reranker");
        assertThat(json.path("vectorLatencyMs").asLong()).isGreaterThanOrEqualTo(5);
        assertThat(json.path("rerankLatencyMs").asLong()).isGreaterThanOrEqualTo(5);
        assertThat(json.path("totalLatencyMs").asLong()).isGreaterThanOrEqualTo(
                json.path("vectorLatencyMs").asLong() + json.path("rerankLatencyMs").asLong());
        assertThat(json.path("topDocuments")).hasSize(3);
        var top = json.path("topDocuments").get(0);
        assertThat(top.path("sourceFile").asText()).isEqualTo("one.md");
        assertThat(top.path("rank").asInt()).isEqualTo(1);
        assertThat(top.path("rerankScore").asDouble()).isEqualTo(.91);
        assertThat(top.path("vectorScore").asDouble()).isBetween(.69, .71);
        assertThat(top.has("content")).isFalse();
    }

    @Test
    void emptyRetrievalNeverClaimsAnUnperformedRerank() {
        when(search.searchSimilarDocuments("question", 10)).thenReturn(List.of());
        var service = pipeline(true);
        var result = service.retrieve("question", 3);
        assertThat(result.isRerankAttempted()).isFalse();
        assertThat(result.isRerankApplied()).isFalse();
        assertThat(result.getCandidateCount()).isZero();
        assertThat(result.getRerankLatencyMs()).isZero();
        verifyNoInteractions(rerank);
    }

    @Test
    void degradedTraceReportsTimeoutAndNeverLabelsVectorScoreAsRerankScore() throws Exception {
        var docs = List.of(document("fallback.md", .7f, null));
        when(search.searchSimilarDocuments("question", 10)).thenReturn(docs);
        when(rerank.rerank("question", docs, 3))
                .thenThrow(new IllegalStateException(new HttpTimeoutException("timeout")));
        var service = pipeline(true);
        var trace = service.trace("question", "standard", service.retrieve("question", 3));
        var json = new ObjectMapper().valueToTree(trace);
        assertThat(json.path("degraded").asBoolean()).isTrue();
        assertThat(json.path("timeout").asBoolean()).isTrue();
        assertThat(json.path("rerankApplied").asBoolean()).isFalse();
        assertThat(json.path("failureType").asText()).isEqualTo("HttpTimeoutException");
        assertThat(json.path("topDocuments").get(0).path("rerankScore").isNull()).isTrue();
    }

    @Test
    void ordinaryAgentToolDoesNotReceiveUiOnlyMeasurements() throws Exception {
        when(search.searchSimilarDocuments("question", 3))
                .thenReturn(List.of(document("one.md", .7f, null)));
        var tool = new InternalDocsTools(pipeline(false));
        var json = new ObjectMapper().readTree(tool.queryInternalDocs("question"));
        assertThat(json.has("retrieval")).isFalse();
        assertThat(json.path("documents")).hasSize(1);
    }

    @Test
    void toolKeepsTraceEvenWhenNoDocumentsExist() throws Exception {
        when(search.searchSimilarDocuments("question", 3)).thenReturn(List.of());
        var tool = new InternalDocsTools(pipeline(false));
        var json = new ObjectMapper().readTree(tool.queryInternalDocsWithTrace("question"));
        assertThat(json.path("status").asText()).isEqualTo("no_results");
        assertThat(json.path("retrieval").path("candidateCount").asInt(-1)).isZero();
        assertThat(json.path("retrieval").path("rerankAttempted").asBoolean()).isFalse();
    }
}
