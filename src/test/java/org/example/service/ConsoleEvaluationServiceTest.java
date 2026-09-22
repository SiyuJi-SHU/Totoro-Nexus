package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import static org.assertj.core.api.Assertions.*;

class ConsoleEvaluationServiceTest {
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void sortsRecordedTimesKeepsExactMetricsAndDoesNotLabelUnknownKAsRecall3() throws Exception {
        write("model-upgrade/results/new.json", json("2026-09-10T01:00:00+08:00", "0.9285714285714286", "3"));
        write("model-upgrade/results/old.json", json("2026-09-09T23:00:00+08:00", "0.9", "5"));
        write("reports/new.md", "# RAG Eval Report\nRecall@1: 92.86%");
        var service = new ConsoleEvaluationService(root.toString());
        var history = mapper.valueToTree(service.history(30));
        assertThat(history.path("available").asBoolean()).isTrue();
        assertThat(history.path("total").asInt()).isEqualTo(2);
        var record = history.path("records").get(0);
        assertThat(record.path("name").asText()).isEqualTo("new");
        assertThat(record.path("recallAt1").asDouble()).isEqualTo(0.9285714285714286);
        assertThat(record.path("recallAt3").asDouble()).isEqualTo(1.0);
        assertThat(record.path("gate").path("status").asText()).isEqualTo("pass");
        assertThat(history.path("records").get(1).path("recallAt3").isNull()).isTrue();
        assertThat(history.path("records").get(1).path("gate").path("status").asText()).isEqualTo("fail");
        assertThat(mapper.valueToTree(service.history(1)).path("records")).hasSize(1);
        assertThat(mapper.valueToTree(service.history(30)).path("records").get(0).path("id")).isEqualTo(record.path("id"));
        var detail = mapper.valueToTree(service.detail(record.path("id").asText()));
        assertThat(detail.path("cases")).hasSize(1);
        assertThat(detail.path("cases").get(0).path("question").asText()).isEqualTo("Where is the runbook?");
        assertThat(detail.path("rawContent").asText()).contains("generatedAt");
    }

    @Test void excludesInvalidAndUnrelatedArtifactsWithExplicitFileTimestampFallback() throws Exception {
        write("reports/legacy.md", "# RAG Eval Report\nRecall@1: 100%");
        write("model-upgrade/results/fallback.json", json("invalid", "0.9", "3"));
        write("model-upgrade/results/invalid.json", json("invalid", "\"NaN\"", "3"));
        write("model-upgrade/results/out-of-range.json", json("invalid", "1.1", "3"));
        write("model-upgrade/results/broken.json", "{broken");
        write("model-upgrade/results/not-evaluation.json", "{\"sourceChunks\":459}");
        write("outside-results.json", json("invalid", "1", "3"));
        var service = new ConsoleEvaluationService(root.toString());
        var history = mapper.valueToTree(service.history(30));
        assertThat(history.path("total").asInt()).isEqualTo(1);
        var record = history.path("records").get(0);
        assertThat(record.path("name").asText()).isEqualTo("fallback");
        assertThat(record.path("timeSource").asText()).isEqualTo("file_modified");
        assertThat(record.path("generatedAt").asText()).isEqualTo("1970-01-01T00:00:00.001Z");
        assertThat(record.path("gate").path("status").asText()).isEqualTo("fail");
        assertThatThrownBy(() -> service.detail("../PLAN.md")).isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        assertThatThrownBy(() -> service.download("0".repeat(24))).isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        assertThat(mapper.valueToTree(new ConsoleEvaluationService(root.resolve("absent").toString()).history(30)).path("available").asBoolean()).isFalse();
    }

    private void write(String path, String text) throws Exception {
        Path file = root.resolve(path); Files.createDirectories(file.getParent()); Files.writeString(file, text);
        Files.setLastModifiedTime(file, FileTime.fromMillis(1));
    }
    private String json(String at, String recall, String k) {
        return """
            {"generatedAt":"%s","resultTopK":%s,"embeddingModel":"embed","rerankModel":"rank",
            "summary":{"totalCases":70,"recallAt1":%s,"recallAtK":1.0,"mrr":0.95,"averageLatencyMs":123.5},
            "results":[{"id":"case-1","question":"Where is the runbook?","hitRank":1,
            "expectedSourceFile":"expected.md","retrievedFiles":["expected.md"],"latencyMs":123}]}
            """.formatted(at, k, recall);
    }
}
