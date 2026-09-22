package org.example.eval.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.service.RagService;
import org.example.service.VectorSearchService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagEvalRunnerTest {

    @TempDir
    Path tempDir;

    @Test
    void evaluatesTheSameRetrievalPipelineUsedByRagGeneration() throws Exception {
        Path cases = tempDir.resolve("cases.jsonl");
        Files.writeString(cases, """
                {"id":"case-1","question":"question","expectedSourceFile":"source.md","expectedKeywords":["evidence"],"difficulty":"hard","topK":3}
                """);
        RagService ragService = mock(RagService.class);
        VectorSearchService.SearchResult searchResult = new VectorSearchService.SearchResult();
        searchResult.setSourceFile("source.md");
        searchResult.setContent("the expected evidence is present");
        when(ragService.retrieveRelevantDocuments("question", 3))
                .thenReturn(List.of(searchResult));
        RagEvalRunner runner = new RagEvalRunner(
                ragService,
                new DefaultResourceLoader(),
                new ObjectMapper(),
                cases.toUri().toString());

        RagEvalRun run = runner.run();

        assertThat(run.getSummary().getRecallAt1()).isEqualTo(1.0);
        assertThat(run.getSummary().getAverageKeywordHitRate()).isEqualTo(1.0);
        verify(ragService).retrieveRelevantDocuments("question", 3);
    }
}
