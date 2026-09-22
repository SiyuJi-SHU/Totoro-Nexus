package org.example.platform;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** Model-selected source spans avoid asking a generative model to retype exact quotes. */
final class SourceSpans {
    record Span(String id,int start,int end,String text) {}
    private SourceSpans() {}
    static List<Span> split(String text) {
        List<Span> result=new ArrayList<>();int start=0;
        while(start<text.length()) {
            int end=Math.min(text.length(),start+600);
            if(end<text.length()) {
                int boundary=text.lastIndexOf('\n',end-1);
                if(boundary>start+80)end=boundary+1;
                else {
                    int sentence=Math.max(text.lastIndexOf(". ",end-1),text.lastIndexOf("。",end-1));
                    if(sentence>start+80)end=sentence+1;
                }
                if(end<text.length()&&Character.isLowSurrogate(text.charAt(end)))end--;
            }
            result.add(new Span("s"+result.size(),start,end,text.substring(start,end)));start=end;
        }
        return List.copyOf(result);
    }
    static JsonNode forModel(JsonNode value) {
        var copy=value.deepCopy();annotate(copy);return copy;
    }
    private static void annotate(JsonNode value) {
        if(value.isObject()&&value.has("id")&&value.has("kind")&&value.path("content").isTextual()) {
            StringBuilder content=new StringBuilder();for(var span:split(value.path("content").asText()))content.append('[').append(span.id()).append("] ").append(span.text()).append('\n');
            ((ObjectNode)value).put("content",content.toString());
            ((ObjectNode)value).put("citationProtocol","本窗口的引用ID是 "+value.path("id").asText()+"。引用示例：{\"id\":\""+value.path("id").asText()+"\",\"spanIds\":[\"s0\"]}，请选实际支持结论的段落。documentId/version用于读取工具，不能作为引用ID；段落编号仅在当前窗口内有效。程序回填原文。");
            return;
        }
        if(value.isContainerNode())value.forEach(SourceSpans::annotate);
    }
    static String resolveDraft(String draft,Map<String,AgentToolRegistry.Evidence> evidence,ObjectMapper json)throws Exception {
        var answer=json.readTree(draft.strip().replaceFirst("^```(?:json)?\\s*","").replaceFirst("\\s*```$",""));
        if(answer.has("citations"))resolveCitations(answer,evidence,json);
        for(String field:List.of("findings","actions","contradictions"))for(var item:answer.path(field)) {
            resolveCitations(item,evidence,json);
        }
        return answer.toString();
    }
    private static void resolveCitations(JsonNode item,Map<String,AgentToolRegistry.Evidence> evidence,ObjectMapper json) {
            var refs=json.createArrayNode();
            for(var ref:item.path("citations")) {
                if(!ref.has("spanIds")){refs.add(ref);continue;}
                String id=ref.path("id").asText();var source=evidence.get(id);var selected=ref.path("spanIds");
                var spans=source==null?List.<Span>of():split(source.content());List<String> quotes=new ArrayList<>();
                boolean valid=selected.isArray()&&!selected.isEmpty()&&selected.size()<=6;
                if(valid)for(var s:selected) {
                    var found=spans.stream().filter(span->span.id().equals(s.asText())).findFirst();
                    if(found.isEmpty()){valid=false;break;}quotes.add(found.get().text());
                }
                if(!valid)refs.addObject().put("id",id).put("quote","");
                else for(String quote:quotes.stream().distinct().toList())refs.addObject().put("id",id).put("quote",quote);
            }
            if(item instanceof ObjectNode object)object.set("citations",refs);
    }
}
