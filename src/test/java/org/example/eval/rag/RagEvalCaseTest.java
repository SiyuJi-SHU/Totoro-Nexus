package org.example.eval.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RagEvalCaseTest {

    @Test
    void deserializesDifficultyAndOriginWithStrictObjectMapper() throws Exception {
        String json = """
                {
                  "id": "rag_v3_test",
                  "question": "如何定位告警？",
                  "expectedSourceFile": "opensource/example.md",
                  "expectedKeywords": ["alert"],
                  "difficulty": "hard",
                  "topK": 3,
                  "origin": "knowledge_base"
                }
                """;

        RagEvalCase evalCase = new ObjectMapper().readValue(json, RagEvalCase.class);

        assertThat(evalCase)
                .extracting("difficulty", "origin")
                .containsExactly("hard", "knowledge_base");
    }
}
