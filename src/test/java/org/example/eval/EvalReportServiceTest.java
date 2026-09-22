package org.example.eval;

import org.example.eval.rag.RagEvalResult;
import org.example.eval.rag.RagEvalSummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvalReportServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void writesRagEvalMarkdownReport() throws Exception {
        RagEvalSummary summary = new RagEvalSummary();
        summary.setTotalCases(1);
        summary.setRecallAt1(1.0);
        summary.setRecallAtK(1.0);
        summary.setMrr(1.0);
        summary.setAverageKeywordHitRate(0.75);
        summary.setAverageLatencyMs(123.0);

        RagEvalResult result = new RagEvalResult();
        result.setId("rag_cpu_001");
        result.setQuestion("CPU usage is high");
        result.setExpectedSourceFile("cpu_high_usage.md");
        result.setRetrievedFiles(List.of("cpu_high_usage.md"));
        result.setHitRank(1);
        result.setKeywordHitRate(0.75);
        result.setLatencyMs(123);

        Path output = tempDir.resolve("rag-eval-report.md");
        Path written = new EvalReportService().writeRagReport(summary, List.of(result), output.toString());

        String markdown = Files.readString(written);
        assertThat(markdown).contains("# RAG Eval Report");
        assertThat(markdown).contains("Recall@1");
        assertThat(markdown).contains("100.00%");
        assertThat(markdown).contains("rag_cpu_001");
    }
}
