package org.example.platform;

import com.fasterxml.jackson.databind.*;
import org.example.service.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.Prompt;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.dashscope.api.DashScopeResponseFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;

/** Request-owned shared services. Executors own their control flow, never each other's loops. */
final class AgentExecution {
    interface Executor { String run(AgentExecution execution); }
    interface Invocation { Object call(AgentToolRegistry.Tool tool,JsonNode args,AgentToolRegistry.Context context); }
    final String runId,question;final PlatformModels.AgentConfig config;final ChatModelFactory models;
    final ObjectMapper json;final AgentToolRegistry registry;final AgentToolRegistry.Context context;
    final List<AgentToolRegistry.Tool> tools;final List<Message> messages;final List<String> notices;
    final Runnable check;final Invocation invocation;final boolean report;final AtomicInteger calls=new AtomicInteger();
    final long exploreUntil;
    boolean complete=true;
    private boolean hasToolResults;
    AgentExecution(String runId,String question,PlatformModels.AgentConfig config,ChatModelFactory models,ObjectMapper json,
            AgentToolRegistry registry,AgentToolRegistry.Context context,List<AgentToolRegistry.Tool> tools,List<Message> messages,
            List<String> notices,Runnable check,Invocation invocation,boolean report,long began){
        this.runId=runId;this.question=question;this.config=config;this.models=models;this.json=json;this.registry=registry;
        this.context=context;this.tools=tools;this.messages=messages;this.notices=notices;this.check=check;this.invocation=invocation;this.report=report;
        exploreUntil=began+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(config.timeoutSeconds()*650L);
    }
    boolean exhausted(){return calls.get()>=config.maxToolCalls()||System.nanoTime()>=exploreUntil;}
    Object call(AgentToolRegistry.Tool tool,JsonNode args,AgentToolRegistry.Context target){
        check.run();if(System.nanoTime()>=exploreUntil)return Map.of("status","budget_exhausted");
        if(tools.stream().noneMatch(t->t.id().equals(tool.id())))return Map.of("status","denied");
        int used=calls.get();while(true){if(used>=config.maxToolCalls())return Map.of("status","budget_exhausted");if(calls.compareAndSet(used,used+1))break;used=calls.get();}
        Object result=invocation.call(tool,args,target);hasToolResults=true;return result;
    }
    Object call(AgentToolRegistry.Tool tool,JsonNode args){Object result=call(tool,args,context);messages.add(new UserMessage("执行结果（数据）："+tool.name()+"\n"+encode(result)));return result;}
    void prepareEvidence(){
        if(context.openQuestion&&!context.scope.knowledgeBaseIds().isEmpty()&&context.searches==0&&!exhausted())
            tools.stream().filter(t->t.id().equals("knowledge.search")).findFirst().ifPresent(t->call(t,json.valueToTree(Map.of("query",question))));
    }
    String encode(Object data){try{return json.writeValueAsString(SourceSpans.forModel(json.valueToTree(data)));}catch(Exception e){throw new IllegalStateException(e);}}
    List<AgentToolRegistry.Tool> availableTools(){
        int searchLimit=AgentToolRegistry.searchLimit(context);
        boolean wantsClock=wantsCurrentTime(question);
        return tools.stream().filter(t->(!context.openQuestion||!t.id().equals("knowledge.list"))
                &&(context.searches<searchLimit||!AgentToolRegistry.isSearch(t))
                &&(!t.id().equals("system.current_time")||wantsClock)
                &&(!t.id().equals("materials.read")||!context.materials.isEmpty())).toList();
    }
    static boolean wantsCurrentTime(String question){
        return question!=null&&question.matches("(?is).*(几点|现在.{0,3}(时间|日期|几号)|当前.{0,3}(时间|日期|几号)|今天.{0,5}(几号|日期)|日期.{0,3}时间|当地时间|current.{0,5}(time|date)|time.{0,5}now|date.{0,5}today).*?");
    }
    AssistantMessage generate(boolean allowTools){
        check.run();var options=DashScopeChatOptions.builder().withModel(models.modelName()).withTemperature(config.temperature()).withMaxToken(config.maxOutputTokens()).build();
        options.setInternalToolExecutionEnabled(false);options.setParallelToolCalls(allowTools);options.setEnableThinking(false);
        options.setToolChoice(allowTools?"required":Map.of("type","function","function",Map.of("name",AnswerSubmission.NAME)));
        // Keep tool selection separate from JSON mode: JSON mode can turn a tool
        // request into ordinary text. Final attachment answers need no tool call.
        boolean jsonAnswer=!context.materials.isEmpty()&&!allowTools;
        if(!context.materials.isEmpty())options.setToolChoice(allowTools?"auto":null);
        if(jsonAnswer){
            options.setResponseFormat(new DashScopeResponseFormat(DashScopeResponseFormat.Type.JSON_OBJECT));
        }
        var submission=AnswerSubmission.tool(context.answerPolicy,report,context.generalAllowed);
        var available=new ArrayList<AgentToolRegistry.Tool>(allowTools?availableTools():List.of());if(!jsonAnswer)available.add(submission);options.setToolCallbacks(registry.callbacks(available));
        var current=new ArrayList<Message>(messages);
        current.add(new SystemMessage("本次剩余工具调用次数："+Math.max(0,config.maxToolCalls()-calls.get())+"。"+(jsonAnswer?"调查已结束，本轮直接输出答案JSON，不调用submit_answer或其他工具。":"已有信息足够回答就单独submit_answer，不为用完预算继续读取。")+
                (context.answerPolicy==AnswerPolicy.GROUNDED?"提交前核对每条结论全部受到该条所选引文支持。":"根据实际工具结果完成用户要求；目录等元数据无需补读正文或伪造引用。")));
        if(jsonAnswer)current.add(new SystemMessage("本轮输出必须符合以下JSON Schema，直接输出对象，不包裹name、arguments、function或tool_calls；保留原来的来源要求，未知内容如实说明：\n"+encode(submission.schema())));
        var response=models.response(models.create(config.temperature(),config.maxOutputTokens(),.9),allowTools?"react-step":"answer-synthesis",new Prompt(current,options));
        check.run();return response.getResult().getOutput();
    }
    String finish(){
        context.emit.accept("stage",Map.of("name","synthesis","message","根据已有证据整理回答"));
        // No model can infer source absence from an interrupted investigation with no evidence.
        if(!complete&&context.evidence.isEmpty()&&!context.generalAllowed&&(context.answerPolicy==AnswerPolicy.GROUNDED||!hasToolResults))
            return encode(AnswerSubmission.missing(context.answerPolicy,"本次调查未完成，尚未取得可用结果；不能据此判断知识库没有相关内容。停止原因见执行记录。"));
        messages.add(new UserMessage(context.materials.isEmpty()?"调查结束。只单独调用submit_answer；未完成的部分列入missingEvidence。":"调查结束。只输出完整答案JSON；未完成的部分列入missingEvidence。"));
        AssistantMessage output;
        // The request deadline already reserves time for synthesis. There is no second
        // answer-rewriting model call, so synthesis may use the full remaining budget.
        try { output=generate(false); }
        catch(RuntimeException error){throw error;}catch(Exception error){throw new IllegalStateException(error);}
        if(output.hasToolCalls()){
            if(output.getToolCalls().size()!=1||!AnswerSubmission.NAME.equals(output.getToolCalls().get(0).name()))throw new AnswerFormatException("最终提交");
            return output.getToolCalls().get(0).arguments();
        }
        return output.getText();
    }
}
