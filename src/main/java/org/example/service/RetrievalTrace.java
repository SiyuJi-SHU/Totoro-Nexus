package org.example.service;

import java.util.List;
import java.util.stream.IntStream;

/** Compact, request-local measurements. No document body or endpoint credentials. */
public record RetrievalTrace(
        String query, String purpose, String embeddingModel, String rerankModel,
        int candidateCount, int resultCount, long vectorLatencyMs, long rerankLatencyMs,
        long totalLatencyMs, boolean rerankAttempted, boolean rerankApplied,
        boolean degraded, String failureType, boolean timeout, List<Document> topDocuments) {

    public record Document(int rank, String sourceFile, String title, Integer chunkIndex,
                           float vectorScore, Double rerankScore,
                           @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String content,
                           @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) Integer vectorRank) {
        public Document(int rank, String sourceFile, String title, Integer chunkIndex, float vectorScore,
                        Double rerankScore, String content) { this(rank, sourceFile, title, chunkIndex, vectorScore, rerankScore, content, null); }
    }

    public static RetrievalTrace from(String query, String purpose, String embeddingModel,
                                      String rerankModel, RetrievalPipelineService.RetrievalResult result) {
        return build(query, purpose, embeddingModel, rerankModel, result, false);
    }

    public static RetrievalTrace console(String query, String embeddingModel, String rerankModel,
                                          RetrievalPipelineService.RetrievalResult result) {
        return build(query, "console-recall-test", embeddingModel, rerankModel, result, true);
    }

    private static RetrievalTrace build(String query, String purpose, String embeddingModel, String rerankModel,
                                         RetrievalPipelineService.RetrievalResult result, boolean previewEnabled) {
        var documents = result.getDocuments();
        var top = IntStream.range(0, Math.min(previewEnabled ? 10 : 3, documents.size())).mapToObj(index -> {
            var doc = documents.get(index);
            // 获取内容预览（前300字符）
            String content = doc.getContent();
            String preview = (content != null && content.length() > 300)
                ? content.substring(0, 300)
                : content;
            return new Document(index + 1, doc.getSourceFile(), doc.getTitle(), doc.getChunkIndex(),
                    doc.getScore(), result.isRerankApplied() ? doc.getRerankScore() : null,
                    previewEnabled ? preview : null, previewEnabled ? result.vectorRank(doc) : null);
        }).toList();
        return new RetrievalTrace(query, purpose, embeddingModel, rerankModel,
                result.getCandidateCount(), documents.size(), result.getVectorLatencyMs(),
                result.getRerankLatencyMs(), result.getRetrievalLatencyMs(),
                result.isRerankAttempted(), result.isRerankApplied(), result.isDegraded(),
                result.getFailureType(), result.isTimeout(), top);
    }
}
