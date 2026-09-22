package org.example.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Shared vector retrieval and optional reranking for RAG and Agent tools. */
@Service
public class RetrievalPipelineService {

    private static final Logger logger = LoggerFactory.getLogger(RetrievalPipelineService.class);

    private final VectorSearchService vectorSearchService;
    private final RerankService rerankService;
    private final boolean rerankEnabled;
    private final int candidateTopK;
    private final Counter fallbackCounter;
    private final Counter timeoutCounter;
    private final Timer rerankTimer;

    @Value("${dashscope.embedding.model}")
    private String embeddingModel;

    @Value("${dashscope.rerank.model:gte-rerank-v2}")
    private String rerankModel;

    public RetrievalTrace trace(String query, String purpose, RetrievalResult result) {
        return RetrievalTrace.from(query, purpose, embeddingModel, rerankModel, result);
    }

    @Autowired
    public RetrievalPipelineService(
            VectorSearchService vectorSearchService,
            RerankService rerankService,
            MeterRegistry meterRegistry,
            @Value("${rag.rerank.enabled:true}") boolean rerankEnabled,
            @Value("${rag.rerank.candidate-top-k:10}") int candidateTopK) {
        this.vectorSearchService = vectorSearchService;
        this.rerankService = rerankService;
        this.rerankEnabled = rerankEnabled;
        this.candidateTopK = candidateTopK;
        this.fallbackCounter = Counter.builder("rag.rerank.fallback")
                .description("Number of requests degraded to vector-only results")
                .register(meterRegistry);
        this.timeoutCounter = Counter.builder("rag.rerank.timeout")
                .description("Number of rerank requests that timed out")
                .register(meterRegistry);
        this.rerankTimer = Timer.builder("rag.rerank.duration")
                .description("Duration of rerank attempts")
                .register(meterRegistry);
    }

    public RetrievalResult retrieve(String query, int resultLimit) {
        return retrieve(query, resultLimit, rerankEnabled ? Math.max(candidateTopK, resultLimit) : resultLimit, rerankEnabled);
    }

    /** Per-request controls for the console; never mutate the application's retrieval defaults. */
    public RetrievalResult retrieve(String query, int resultLimit, int candidatesRequested, boolean useRerank) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("retrieval query must not be blank");
        }
        if (resultLimit < 1) {
            throw new IllegalArgumentException("resultLimit must be at least 1");
        }

        long pipelineStarted = System.nanoTime();
        if (candidatesRequested < resultLimit || candidatesRequested > 1000) {
            throw new IllegalArgumentException("candidate count must cover the result limit");
        }
        if (!useRerank) {
            List<VectorSearchService.SearchResult> documents =
                    vectorSearchService.searchSimilarDocuments(query, candidatesRequested);
            long vectorMs = elapsedMillis(pipelineStarted);
            return RetrievalResult.vectorOnly(first(documents, resultLimit), vectorMs)
                    .withStageDetails(documents.size(), vectorMs, 0).withCandidates(documents);
        }

        int candidateLimit = candidatesRequested;
        List<VectorSearchService.SearchResult> candidates =
                vectorSearchService.searchSimilarDocuments(query, candidateLimit);
        long vectorMs = elapsedMillis(pipelineStarted);
        if (candidates.isEmpty()) {
            return RetrievalResult.vectorOnly(List.of(), elapsedMillis(pipelineStarted))
                    .withStageDetails(0, vectorMs, 0);
        }

        long rerankStarted = System.nanoTime();
        try {
            List<VectorSearchService.SearchResult> documents =
                    rerankService.rerank(query, candidates, resultLimit);
            long rerankMs = elapsedMillis(rerankStarted);
            recordRerankDuration(rerankStarted);
            logger.info(
                    "Rerank completed, candidateCount={}, resultCount={}, rerankLatencyMs={}, degraded=false",
                    candidates.size(),
                    documents.size(),
                    elapsedMillis(rerankStarted));
            return RetrievalResult.reranked(documents, elapsedMillis(pipelineStarted))
                    .withStageDetails(candidates.size(), vectorMs, rerankMs).withCandidates(candidates);
        } catch (RuntimeException error) {
            long rerankMs = elapsedMillis(rerankStarted);
            recordRerankDuration(rerankStarted);
            fallbackCounter.increment();
            boolean timeout = isTimeout(error);
            if (timeout) {
                timeoutCounter.increment();
            }
            String errorType = rootCause(error).getClass().getSimpleName();
            List<VectorSearchService.SearchResult> fallback = first(candidates, resultLimit);
            logger.warn(
                    "Rerank failed; using vector fallback, candidateCount={}, resultCount={}, "
                            + "rerankLatencyMs={}, degraded=true, timeout={}, errorType={}",
                    candidates.size(),
                    fallback.size(),
                    elapsedMillis(rerankStarted),
                    timeout,
                    errorType,
                    error);
            return RetrievalResult.degraded(
                    fallback,
                    elapsedMillis(pipelineStarted),
                    errorType,
                    timeout).withStageDetails(candidates.size(), vectorMs, rerankMs).withCandidates(candidates);
        }
    }

    private void recordRerankDuration(long startedNanos) {
        rerankTimer.record(System.nanoTime() - startedNanos, TimeUnit.NANOSECONDS);
    }

    private List<VectorSearchService.SearchResult> first(
            List<VectorSearchService.SearchResult> candidates,
            int resultLimit) {
        int end = Math.min(resultLimit, candidates.size());
        return new ArrayList<>(candidates.subList(0, end));
    }

    private boolean isTimeout(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof HttpTimeoutException || current instanceof TimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    public static final class RetrievalResult {
        private java.util.Map<String, Integer> vectorRanks = java.util.Map.of();

        private static String key(VectorSearchService.SearchResult d) {
            return d.getSourceFile() + "\u0000" + d.getChunkIndex();
        }
        private RetrievalResult withCandidates(List<VectorSearchService.SearchResult> candidates) {
            var ranks = new java.util.HashMap<String, Integer>();
            for (int i = 0; i < candidates.size(); i++) ranks.putIfAbsent(key(candidates.get(i)), i + 1);
            vectorRanks = java.util.Map.copyOf(ranks);
            return this;
        }
        public Integer vectorRank(VectorSearchService.SearchResult document) { return vectorRanks.get(key(document)); }
        private final List<VectorSearchService.SearchResult> documents;
        private final boolean rerankAttempted;
        private final boolean rerankApplied;
        private final boolean degraded;
        private final long retrievalLatencyMs;
        private final String failureType;
        private final boolean timeout;
        private final int candidateCount;
        private final long vectorLatencyMs;
        private final long rerankLatencyMs;

        private RetrievalResult(
                List<VectorSearchService.SearchResult> documents,
                boolean rerankAttempted,
                boolean rerankApplied,
                boolean degraded,
                long retrievalLatencyMs,
                String failureType,
                boolean timeout) {
            this(documents, rerankAttempted, rerankApplied, degraded, retrievalLatencyMs,
                    failureType, timeout, documents.size(), retrievalLatencyMs, 0);
        }

        private RetrievalResult(List<VectorSearchService.SearchResult> documents,
                boolean rerankAttempted, boolean rerankApplied, boolean degraded,
                long retrievalLatencyMs, String failureType, boolean timeout,
                int candidateCount, long vectorLatencyMs, long rerankLatencyMs) {
            this.documents = Collections.unmodifiableList(new ArrayList<>(documents));
            this.rerankAttempted = rerankAttempted;
            this.rerankApplied = rerankApplied;
            this.degraded = degraded;
            this.retrievalLatencyMs = retrievalLatencyMs;
            this.failureType = failureType;
            this.timeout = timeout;
            this.candidateCount = candidateCount;
            this.vectorLatencyMs = vectorLatencyMs;
            this.rerankLatencyMs = rerankLatencyMs;
        }

        private RetrievalResult withStageDetails(int count, long vectorMs, long rerankMs) {
            return new RetrievalResult(documents, rerankAttempted, rerankApplied, degraded,
                    retrievalLatencyMs, failureType, timeout, count, vectorMs, rerankMs);
        }

        public int getCandidateCount() { return candidateCount; }
        public long getVectorLatencyMs() { return vectorLatencyMs; }
        public long getRerankLatencyMs() { return rerankLatencyMs; }

        public static RetrievalResult vectorOnly(
                List<VectorSearchService.SearchResult> documents,
                long retrievalLatencyMs) {
            return new RetrievalResult(
                    documents, false, false, false, retrievalLatencyMs, null, false);
        }

        public static RetrievalResult reranked(
                List<VectorSearchService.SearchResult> documents,
                long retrievalLatencyMs) {
            return new RetrievalResult(
                    documents, true, true, false, retrievalLatencyMs, null, false);
        }

        public static RetrievalResult degraded(
                List<VectorSearchService.SearchResult> documents,
                long retrievalLatencyMs,
                String failureType,
                boolean timeout) {
            return new RetrievalResult(
                    documents, true, false, true, retrievalLatencyMs, failureType, timeout);
        }

        public List<VectorSearchService.SearchResult> getDocuments() {
            return documents;
        }

        public boolean isRerankAttempted() {
            return rerankAttempted;
        }

        public boolean isRerankApplied() {
            return rerankApplied;
        }

        public boolean isDegraded() {
            return degraded;
        }

        public long getRetrievalLatencyMs() {
            return retrievalLatencyMs;
        }

        public String getFailureType() {
            return failureType;
        }

        public boolean isTimeout() {
            return timeout;
        }
    }
}
