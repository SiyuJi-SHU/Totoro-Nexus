package org.example.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AnswerFormatExceptionTest {
    @Test void upstreamFailureIncludesSafeReasonWithoutEchoingResponseBody() {
        var error=new org.springframework.ai.retry.NonTransientAiException("401 - {\"message\":\"Invalid API-key provided\",\"secret\":\"do-not-echo\"}");
        assertThat(AgentRuntime.safeError(error)).contains("HTTP 401","鉴权失败").doesNotContain("do-not-echo");
    }
    @Test void emptyEvidenceAnswerIsValidButTruncatedOrWrongShapeOutputIsAFailure() {
        var json=new ObjectMapper();
        assertThatCode(()->AnswerFormatException.require("{\"findings\":[],\"actions\":[],\"missingEvidence\":[\"缺少资料\"]}",json,"审校")).doesNotThrowAnyException();
        for(String invalid:new String[]{"2.5, DexiNed", "{}", "{\"findings\":[]", "{\"findings\":[],\"actions\":[],\"missingEvidence\":[]} extra"})
            assertThatThrownBy(()->AnswerFormatException.require(invalid,json,"审校")).isInstanceOf(AnswerFormatException.class);
    }
    @Test void answerToolRequiresCompleteDirectFindings() throws Exception {
        String schema=new ObjectMapper().writeValueAsString(AnswerSubmission.tool(false,false).schema());
        assertThat(schema).contains("完整陈述句","如何处理","不能用定义或分类背景顶替");
    }
}
