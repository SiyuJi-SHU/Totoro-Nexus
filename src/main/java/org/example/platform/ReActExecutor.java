package org.example.platform;

import org.springframework.ai.chat.messages.*;
import org.example.service.ModelDeadline;
import java.util.*;

final class ReActExecutor implements AgentExecution.Executor {
    public String run(AgentExecution e){
        Map<String,Object> requests=new HashMap<>();int repeated=0;
        try(var deadline=ModelDeadline.bind(e.exploreUntil)) {
            e.prepareEvidence();
            // General discussions get one bounded lookup, then answer directly.
            // Extra tool turns tend to read unrelated runbooks without improving the response.
            if(e.context.openQuestion){e.complete=true;return e.finish();}
            for(int turn=0;turn<e.config.maxToolCalls()+1&&!e.exhausted();turn++) {
                e.check.run();e.context.emit.accept("stage",Map.of("name","reasoning","message","分析问题并选择下一步工具","toolCalls",e.calls.get(),"toolBudget",e.config.maxToolCalls()));
                var output=e.generate(true);
                if(!output.hasToolCalls())return e.context.materials.isEmpty()?output.getText():e.finish();
                if(output.getToolCalls().size()==1&&AnswerSubmission.NAME.equals(output.getToolCalls().get(0).name()))return output.getToolCalls().get(0).arguments();
                e.messages.add(output);List<ToolResponseMessage.ToolResponse> responses=new ArrayList<>();boolean progressed=false;
                for(var call:output.getToolCalls()){
                    Object result;var tool=e.tools.stream().filter(t->t.name().equals(call.name())).findFirst();
                    com.fasterxml.jackson.databind.JsonNode args;try{args=e.json.readTree(call.arguments());}catch(Exception bad){args=null;}
                    String requestKey=requestKey(call.name(),args,call.arguments(),e.json);
                    if(AnswerSubmission.NAME.equals(call.name()))result=Map.of("status","invalid_completion","message","处理其他工具结果后再单独提交答案");
                    else if(tool.isEmpty())result=Map.of("status","denied");
                    else if(requests.containsKey(requestKey)){
                        var previous=e.json.valueToTree(requests.get(requestKey));Map<String,Object> duplicate=new LinkedHashMap<>();
                        duplicate.put("status","repeated_request");duplicate.put("message","相同请求已执行，不会获得新证据。当前证据足够就提交答案；确需后续内容，使用nextRead参数；查其他内容则改变查询或来源。");
                        if(previous.has("nextRead"))duplicate.put("nextRead",previous.get("nextRead"));
                        if(previous.has("evidence"))duplicate.put("previousRange",Map.of("start",previous.path("evidence").path("start").asInt(),"end",previous.path("evidence").path("end").asInt(),"totalChars",previous.path("totalChars").asInt()));
                        result=duplicate;e.context.emit.accept("tool_repeat",Map.of("id",tool.get().id(),"arguments",call.arguments()));
                    }
                    else {result=e.call(tool.get(),args,e.context);requests.put(requestKey,result);progressed=true;}
                    responses.add(new ToolResponseMessage.ToolResponse(call.id(),call.name(),e.encode(result)));
                }
                e.messages.add(ToolResponseMessage.builder().responses(responses).build());
                repeated=progressed?0:repeated+1;
                if(repeated>=2){e.notices.add("重复请求未带来新进展，已停止调查");break;}
            }
        }catch(ModelDeadline.LimitException timeout){e.notices.add(System.nanoTime()>=e.exploreUntil?"调查达到时间预算，使用已取得的结果整理答案":"单次模型响应超时，使用已取得的结果整理答案");}
        catch(RuntimeException error){throw error;}catch(Exception error){throw new IllegalStateException(error);}
        e.complete=false;
        if(e.notices.isEmpty())e.notices.add(System.nanoTime()>=e.exploreUntil?"调查达到时间预算，使用已取得的证据整理答案":"调查达到工具或轮次预算，使用已取得的证据整理答案");
        return e.finish();
    }
    static String requestKey(String name,com.fasterxml.jackson.databind.JsonNode args,String raw,com.fasterxml.jackson.databind.ObjectMapper json) {
        // The flat builtin schemas use scalar values; sorting keys also catches equivalent reordered requests.
        return name+":"+(args!=null&&args.isObject()?json.valueToTree(json.convertValue(args,TreeMap.class)).toString():raw);
    }
}
