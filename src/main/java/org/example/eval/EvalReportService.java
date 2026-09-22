package org.example.eval;

import org.example.eval.rag.RagEvalResult;
import org.example.eval.rag.RagEvalSummary;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

@Service
public class EvalReportService {

    private static final DateTimeFormatter REPORT_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public Path writeRagReport(RagEvalSummary summary, List<RagEvalResult> results, String outputPath)
            throws IOException {
        Path path = Path.of(outputPath).normalize();
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(path, buildRagReport(summary, results));
        return path;
    }

    private String buildRagReport(RagEvalSummary summary, List<RagEvalResult> results) {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# RAG Eval Report\n\n");
        markdown.append("- Generated At: ").append(LocalDateTime.now().format(REPORT_TIME_FORMAT)).append("\n");
        markdown.append("- Total Cases: ").append(summary.getTotalCases()).append("\n");
        markdown.append("- Recall@1: ").append(percent(summary.getRecallAt1())).append("\n");
        markdown.append("- Recall@K: ").append(percent(summary.getRecallAtK())).append("\n");
        markdown.append("- MRR: ").append(decimal(summary.getMrr())).append("\n");
        markdown.append("- Keyword Hit Rate: ").append(percent(summary.getAverageKeywordHitRate())).append("\n");
        markdown.append("- Avg Latency: ").append(decimal(summary.getAverageLatencyMs())).append(" ms\n\n");

        markdown.append("## Case Details\n\n");
        markdown.append("| Case | Hit Rank | Expected Source | Retrieved Files | Keyword Hit Rate | Latency |\n");
        markdown.append("|---|---:|---|---|---:|---:|\n");
        for (RagEvalResult result : results) {
            markdown.append("| ")
                    .append(escape(result.getId()))
                    .append(" | ")
                    .append(result.getHitRank())
                    .append(" | ")
                    .append(escape(result.getExpectedSourceFile()))
                    .append(" | ")
                    .append(escape(join(result.getRetrievedFiles())))
                    .append(" | ")
                    .append(percent(result.getKeywordHitRate()))
                    .append(" | ")
                    .append(result.getLatencyMs())
                    .append(" ms |\n");
        }

        markdown.append("\n## Failed Cases\n\n");
        if (summary.getFailedCaseIds().isEmpty()) {
            markdown.append("- None\n");
        } else {
            for (String failedCaseId : summary.getFailedCaseIds()) {
                markdown.append("- ").append(escape(failedCaseId)).append("\n");
            }
        }

        return markdown.toString();
    }

    private String join(List<String> values) {
        StringJoiner joiner = new StringJoiner(", ");
        for (String value : values) {
            joiner.add(value);
        }
        return joiner.toString();
    }

    private String percent(double value) {
        return String.format(Locale.ROOT, "%.2f%%", value * 100.0);
    }

    private String decimal(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("|", "\\|").replace("\n", " ");
    }
}
