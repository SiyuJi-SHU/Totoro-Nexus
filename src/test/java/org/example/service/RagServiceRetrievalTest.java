package org.example.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagServiceRetrievalTest {

    @Test
    void delegatesRetrievalToTheSharedPipeline() {
        RetrievalPipelineService pipeline = mock(RetrievalPipelineService.class);
        List<VectorSearchService.SearchResult> documents =
                List.of(candidate("one"), candidate("two"));
        when(pipeline.retrieve("question", 3)).thenReturn(
                RetrievalPipelineService.RetrievalResult.reranked(documents, 12));
        RagService service = new RagService(pipeline, "test-api-key", 3, "test-model");

        List<VectorSearchService.SearchResult> results =
                service.retrieveRelevantDocuments("question", 3);

        assertThat(results).containsExactlyElementsOf(documents);
        verify(pipeline).retrieve("question", 3);
    }

    private VectorSearchService.SearchResult candidate(String id) {
        VectorSearchService.SearchResult result = new VectorSearchService.SearchResult();
        result.setId(id);
        result.setContent(id + " content");
        return result;
    }
}
