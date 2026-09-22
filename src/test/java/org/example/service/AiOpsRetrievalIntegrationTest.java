package org.example.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.example.agent.tool.InternalDocsTools;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiOpsRetrievalIntegrationTest {

    @Test
    void ragAndAiOpsInternalDocsToolShareTheSameRerankedDocumentOrder() throws Exception {
        VectorSearchService vectorSearch = mock(VectorSearchService.class);
        RerankService rerank = mock(RerankService.class);
        List<VectorSearchService.SearchResult> candidates =
                List.of(candidate("one"), candidate("two"), candidate("three"));
        List<VectorSearchService.SearchResult> reranked =
                List.of(candidates.get(2), candidates.get(0), candidates.get(1));
        when(vectorSearch.searchSimilarDocuments("question", 10)).thenReturn(candidates);
        when(rerank.rerank("question", candidates, 3)).thenReturn(reranked);

        RetrievalPipelineService pipeline = new RetrievalPipelineService(
                vectorSearch, rerank, new SimpleMeterRegistry(), true, 10);
        RagService ragService = new RagService(pipeline, "test-api-key", 3, "test-model");
        InternalDocsTools internalDocsTools = new InternalDocsTools(pipeline);

        List<String> ragOrder = ragService.retrieveRelevantDocuments("question", 3).stream()
                .map(VectorSearchService.SearchResult::getSourceFile)
                .toList();
        JsonNode toolResponse = new ObjectMapper().readTree(
                internalDocsTools.queryInternalDocs("question"));
        List<String> toolOrder = new ArrayList<>();
        toolResponse.path("documents").forEach(
                document -> toolOrder.add(document.path("sourceFile").asText()));

        assertThat(toolResponse.path("status").asText()).isEqualTo("ok");
        assertThat(toolResponse.path("rerankApplied").asBoolean()).isTrue();
        assertThat(toolOrder).containsExactlyElementsOf(ragOrder);
    }

    private VectorSearchService.SearchResult candidate(String id) {
        VectorSearchService.SearchResult result = new VectorSearchService.SearchResult();
        result.setId(id);
        result.setContent(id + " content");
        result.setSourceFile(id + ".md");
        return result;
    }
}
