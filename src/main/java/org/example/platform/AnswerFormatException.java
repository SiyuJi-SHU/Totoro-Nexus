package org.example.platform;

import com.fasterxml.jackson.databind.*;

/** Output protocol failure is distinct from missing evidence. */
final class AnswerFormatException extends RuntimeException {
    AnswerFormatException(String stage) { super(stage + "未返回有效的结构化内容，请重试；这不是知识库无资料"); }
    static void require(String raw, ObjectMapper json, String stage) {
        require(raw,json,stage,AnswerPolicy.GROUNDED);
    }
    static void require(String raw,ObjectMapper json,String stage,AnswerPolicy policy) {
        try {
            if(raw==null||raw.length()>40000)throw new IllegalArgumentException();
            var node=json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(
                    raw.strip().replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", ""));
            if(!node.isObject()||!node.path("missingEvidence").isArray())throw new IllegalArgumentException();
            if(policy==AnswerPolicy.CONVERSATIONAL) {
                if(!node.path("answerText").isTextual()||node.path("answerText").asText().length()>24000
                        ||!node.path("citations").isArray()||node.path("citations").size()>32
                        ||node.has("findings")||node.has("actions")||node.has("generalExplanation")||node.has("contradictions"))throw new IllegalArgumentException();
                if(node.path("answerText").asText().isBlank()&&node.path("missingEvidence").isEmpty())throw new IllegalArgumentException();
                for(var ref:node.path("citations"))if(!ref.isObject()||!ref.path("id").isTextual())throw new IllegalArgumentException();
            } else if(!node.path("findings").isArray()||!node.path("actions").isArray()||node.has("answerText")||node.has("citations"))throw new IllegalArgumentException();
            for(var missing:node.path("missingEvidence"))if(!missing.isTextual()||missing.asText().isBlank()||missing.asText().length()>1000)throw new IllegalArgumentException();
        } catch(Exception ignored) { throw new AnswerFormatException(stage); }
    }
}
