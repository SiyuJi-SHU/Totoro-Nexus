package org.example.controller;

import org.example.service.RagService;
import org.example.service.VectorSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ChatControllerRagEvalTest {

    private ChatController controller;
    private RagService ragService;

    @BeforeEach
    void setUp() {
        controller = new ChatController();
        ragService = mock(RagService.class);
        ReflectionTestUtils.setField(controller, "ragService", ragService);
    }

    @Test
    void ragEvalReturnsTheExactContextsUsedToGenerateTheAnswer() {
        VectorSearchService.SearchResult first = searchResult(
                "first context", "first.md", 2, "First heading", 0.12f);
        VectorSearchService.SearchResult second = searchResult(
                "second context", "second.md", 4, "Second heading", 0.34f);

        doAnswer(invocation -> {
            RagService.StreamCallback callback = invocation.getArgument(1);
            callback.onSearchResults(List.of(first, second));
            callback.onContentChunk("streamed answer");
            callback.onComplete("complete answer", "");
            return null;
        }).when(ragService).queryStream(eq("What happened?"), any(RagService.StreamCallback.class));

        ChatController.ChatRequest request = new ChatController.ChatRequest();
        request.setQuestion("What happened?");

        ResponseEntity<ChatController.ApiResponse<ChatController.RagEvalResponse>> response =
                controller.ragEval(request);

        assertEquals(200, response.getBody().getCode());
        ChatController.RagEvalResponse data = response.getBody().getData();
        assertTrue(data.isSuccess());
        assertEquals("complete answer", data.getAnswer());
        assertEquals(List.of("first context", "second context"), data.getContexts());
        assertEquals(List.of("first.md", "second.md"), data.getSourceFiles());
        assertEquals(List.of(2, 4), data.getChunkIndexes());
        assertEquals(List.of("First heading", "Second heading"), data.getTitles());
        assertEquals(List.of(0.12f, 0.34f), data.getScores());
    }

    @Test
    void ragEvalRejectsBlankQuestionWithoutCallingTheModel() {
        ChatController.ChatRequest request = new ChatController.ChatRequest();
        request.setQuestion("   ");

        ResponseEntity<ChatController.ApiResponse<ChatController.RagEvalResponse>> response =
                controller.ragEval(request);

        ChatController.RagEvalResponse data = response.getBody().getData();
        assertFalse(data.isSuccess());
        assertEquals("问题内容不能为空", data.getErrorMessage());
        assertTrue(data.getContexts().isEmpty());
        verify(ragService, never()).queryStream(any(), any());
    }

    private VectorSearchService.SearchResult searchResult(
            String content,
            String sourceFile,
            int chunkIndex,
            String title,
            float score) {
        VectorSearchService.SearchResult result = new VectorSearchService.SearchResult();
        result.setContent(content);
        result.setSourceFile(sourceFile);
        result.setChunkIndex(chunkIndex);
        result.setTitle(title);
        result.setScore(score);
        return result;
    }
}
