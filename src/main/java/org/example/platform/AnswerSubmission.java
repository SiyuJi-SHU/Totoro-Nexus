package org.example.platform;

import java.util.*;

/** Native completion tool keeps JSON output separate from provider JSON mode, which can suppress tool selection. */
final class AnswerSubmission {
    static final String NAME="submit_answer";
    static Map<String,Object> missing(AnswerPolicy policy,String reason) {
        return policy==AnswerPolicy.CONVERSATIONAL
                ?Map.of("answerText","","citations",List.of(),"missingEvidence",List.of(reason))
                :Map.of("findings",List.of(),"actions",List.of(),"missingEvidence",List.of(reason));
    }
    static AgentToolRegistry.Tool tool(AnswerPolicy policy,boolean incident,boolean generalAllowed) {
        if(policy==AnswerPolicy.GROUNDED)return tool(incident,generalAllowed);
        var citation=object(Map.of(
                "id",Map.of("type","string","description","工具返回的证据窗口id，不是documentId。"),
                "spanIds",Map.of("type","array","items",Map.of("type","string"),"minItems",1,"maxItems",6,"description","选择支持回答的原文段落编号，程序回填原文。"),
                "quote",Map.of("type","string","description","无段落编号时逐字复制连续原文；与spanIds二选一。")),"id");
        var fields=Map.<String,Object>of(
                "answerText",Map.of("type","string","maxLength",24000,"description","直接回答用户的完整正文，可用段落、列表和代码块。根据实际工具结果回答；列举请求应完整列出本次取得的条目并说明截断。没有可回答的信息时留空，在missingEvidence说明原因。"),
                "citations",Map.of("type","array","items",citation,"maxItems",32,"description","正文资料的可选来源。目录、计数、章节等工具元数据可以直接回答，无证据窗口id时留空，不编造引用。"),
                "missingEvidence",Map.of("type","array","items",Map.of("type","string"),"maxItems",3,"description","用户要求但本次未能完成或查证的部分；正常空列表不是缺少证据。"));
        return new AgentToolRegistry.Tool("answer.submit",NAME,"提交回答","任务完成后单独提交正文和可选来源。工具结果可以直接用于回答，不必先读取正文或制造引文。",object(fields,"answerText","citations","missingEvidence"),"运行协议",true);
    }
    static AgentToolRegistry.Tool tool() { return tool(true); }
    static AgentToolRegistry.Tool tool(boolean incident) {
        return tool(incident,false);
    }
    static AgentToolRegistry.Tool tool(boolean incident,boolean generalAllowed) {
        var text=Map.<String,Object>of("type","string");
        var citation=object(Map.of("id",Map.of("type","string","description","复制证据窗口的id（文档证据形如D-...），不要填documentId或version。"),
                "spanIds",Map.of("type","array","items",text,"minItems",1,"maxItems",6,"description","优先使用：选择该来源中直接支持结论的段落编号，例如[s2,s3]，由系统准确回填原文。不能选择仅主题相关但不支持结论的段落。"),
                "quote",Map.of("type","string","description","仅来源未显示段落编号时使用：逐字复制连续原文，保留脚注与表格列，不拼接省略号。spanIds与quote二选一。")),"id");
        var refs=Map.<String,Object>of("type","array","items",citation,"minItems",1);
        var findingFields=new LinkedHashMap<String,Object>();
        findingFields.put("citations",refs);
        findingFields.put("text",Map.of("type","string","description","先选引文，再写这些引文能直接支持的一个主要事实。必须是脱离引文也能读懂的完整陈述句，并直接回答用户问的处理方式、定义或原因；分类背景只能补充，不能提交半句话。不要加入未引用的时间、因果或情节。"));
        findingFields.put("certainty",Map.of("type","string","enum",List.of("observation","supported","hypothesis")));
        var finding=object(findingFields,"citations","text","certainty");
        var action=object(Map.of("text",Map.of("type","string","description","有来源的处理步骤或操作建议。用户明确询问如何处理、步骤或操作时，用action直接回答，不能用定义或分类背景顶替。"),"command",text,"prerequisites",text,"citations",refs),"text","command","prerequisites","citations");
        var fields=new LinkedHashMap<String,Object>();
        fields.put("findings",Map.of("type","array","items",finding,"maxItems",incident?3:12));
        fields.put("actions",Map.of("type","array","items",action,"maxItems",2));
        fields.put("missingEvidence",Map.of("type","array","items",text,"maxItems",3));
        if(!incident&&generalAllowed)fields.put("generalExplanation",Map.of("type","string","description","通识解释或一般分析。不得包含内部/实时/指定文档事实，不得声称来自检索资料，不写现场操作命令。可用自然段和列表；没有补充则空串。"));
        if(incident)fields.put("contradictions",Map.of("type","array","items",finding,"maxItems",2));
        var schema=generalAllowed&&!incident?object(fields,"findings","actions","missingEvidence","generalExplanation"):object(fields,"findings","actions","missingEvidence");
        return new AgentToolRegistry.Tool("answer.submit",NAME,"提交待审答案","调查完成时调用。findings/actions只提交有来源的事实；若允许通识补充，generalExplanation可提供不依赖资料的一般解释。资料不足如实说明。不要与其他工具同时调用。",schema,"运行协议",true);
    }
    private static Map<String,Object> object(Map<String,?> fields,String... required){return Map.of("type","object","properties",fields,"required",List.of(required),"additionalProperties",false);}
}
