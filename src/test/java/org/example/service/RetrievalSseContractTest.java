package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.controller.ChatController.SseMessage;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class RetrievalSseContractTest {
    @Test
    void nestedTraceRemainsAStringAndEscapedTextRoundTrips() throws Exception {
        var mapper = new ObjectMapper();
        var trace = new RetrievalTrace("查询 \"timeout\"\n第二行", "standard", "embedding", "rerank",
                10, 3, 12, 20, 32, true, true, false, null, false,
                List.of(new RetrievalTrace.Document(1, "quotes\".md", "标题", 0, .8f, .9, "预览内容")));
        var envelope = mapper.readTree(mapper.writeValueAsString(SseMessage.retrieval(trace)));
        assertThat(envelope.path("type").asText()).isEqualTo("retrieval");
        assertThat(envelope.path("data").isTextual()).isTrue();
        var payload = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(envelope.path("data").asText());
        assertThat(payload.remove("embeddingDimension").asInt()).isEqualTo(org.example.constant.MilvusConstants.VECTOR_DIM);
        var decoded = mapper.treeToValue(payload, RetrievalTrace.class);
        assertThat(decoded).isEqualTo(trace);
        assertThat(SseMessage.reportStart().getType()).isEqualTo("report-start");
        assertThat(SseMessage.reportStart().getData()).isEmpty();
    }
}
