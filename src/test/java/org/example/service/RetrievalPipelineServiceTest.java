package org.example.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.net.http.HttpTimeoutException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetrievalPipelineServiceTest {

    @Test
    void vectorOnlyModeReturnsTheRequestedTopKWithoutCallingRerank() {
        VectorSearchService vectorSearch = mock(VectorSearchService.class);
        RerankService rerank = mock(RerankService.class);
        List<VectorSearchService.SearchResult> documents =
                List.of(candidate("one"), candidate("two"), candidate("three"));
        when(vectorSearch.searchSimilarDocuments("question", 3)).thenReturn(documents);
        RetrievalPipelineService service = service(
                vectorSearch, rerank, false, new SimpleMeterRegistry());

        RetrievalPipelineService.RetrievalResult result = service.retrieve("question", 3);

        assertThat(result.getDocuments()).containsExactlyElementsOf(documents);
        assertThat(result.isRerankAttempted()).isFalse();
        assertThat(result.isDegraded()).isFalse();
        verify(rerank, never()).rerank("question", documents, 3);
    }

    @Test
    void qualityModeRetrievesTenCandidatesAndReturnsThreeRerankedResults() {
        VectorSearchService vectorSearch = mock(VectorSearchService.class);
        RerankService rerank = mock(RerankService.class);
        List<VectorSearchService.SearchResult> candidates =
                List.of(candidate("one"), candidate("two"), candidate("three"));
        List<VectorSearchService.SearchResult> reranked =
                List.of(candidates.get(2), candidates.get(0), candidates.get(1));
        when(vectorSearch.searchSimilarDocuments("question", 10)).thenReturn(candidates);
        when(rerank.rerank("question", candidates, 3)).thenReturn(reranked);
        RetrievalPipelineService service = service(
                vectorSearch, rerank, true, new SimpleMeterRegistry());

        RetrievalPipelineService.RetrievalResult result = service.retrieve("question", 3);

        assertThat(result.getDocuments()).containsExactlyElementsOf(reranked);
        assertThat(result.isRerankAttempted()).isTrue();
        assertThat(result.isRerankApplied()).isTrue();
        assertThat(result.isDegraded()).isFalse();
        verify(vectorSearch).searchSimilarDocuments("question", 10);
        verify(rerank).rerank("question", candidates, 3);
    }

    @Test
    void rerankFailureFallsBackToTheOriginalVectorTopThreeAndIncrementsCounter() {
        VectorSearchService vectorSearch = mock(VectorSearchService.class);
        RerankService rerank = mock(RerankService.class);
        List<VectorSearchService.SearchResult> candidates = List.of(
                candidate("one"), candidate("two"), candidate("three"), candidate("four"));
        when(vectorSearch.searchSimilarDocuments("question", 10)).thenReturn(candidates);
        when(rerank.rerank("question", candidates, 3))
                .thenThrow(new IllegalStateException("remote failure"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RetrievalPipelineService service = service(vectorSearch, rerank, true, registry);

        RetrievalPipelineService.RetrievalResult result = service.retrieve("question", 3);

        assertThat(result.getDocuments()).containsExactlyElementsOf(candidates.subList(0, 3));
        assertThat(result.isRerankAttempted()).isTrue();
        assertThat(result.isRerankApplied()).isFalse();
        assertThat(result.isDegraded()).isTrue();
        assertThat(result.getFailureType()).isEqualTo("IllegalStateException");
        assertThat(registry.get("rag.rerank.fallback").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("rag.rerank.timeout").counter().count()).isZero();
    }

    @Test
    void rerankTimeoutIncrementsBothFallbackAndTimeoutCounters() {
        VectorSearchService vectorSearch = mock(VectorSearchService.class);
        RerankService rerank = mock(RerankService.class);
        List<VectorSearchService.SearchResult> candidates =
                List.of(candidate("one"), candidate("two"));
        when(vectorSearch.searchSimilarDocuments("question", 10)).thenReturn(candidates);
        when(rerank.rerank("question", candidates, 2)).thenThrow(
                new IllegalStateException(
                        "rerank failed", new HttpTimeoutException("timed out")));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RetrievalPipelineService service = service(vectorSearch, rerank, true, registry);

        RetrievalPipelineService.RetrievalResult result = service.retrieve("question", 2);

        assertThat(result.isDegraded()).isTrue();
        assertThat(result.isTimeout()).isTrue();
        assertThat(result.getFailureType()).isEqualTo("HttpTimeoutException");
        assertThat(registry.get("rag.rerank.fallback").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("rag.rerank.timeout").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("rag.rerank.duration").timer().count()).isEqualTo(1L);
    }

    private RetrievalPipelineService service(
            VectorSearchService vectorSearch,
            RerankService rerank,
            boolean enabled,
            SimpleMeterRegistry registry) {
        return new RetrievalPipelineService(vectorSearch, rerank, registry, enabled, 10);
    }

    private VectorSearchService.SearchResult candidate(String id) {
        VectorSearchService.SearchResult result = new VectorSearchService.SearchResult();
        result.setId(id);
        result.setContent(id + " content");
        result.setSourceFile(id + ".md");
        return result;
    }
}
