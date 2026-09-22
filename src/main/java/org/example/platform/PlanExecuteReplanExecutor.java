package org.example.platform;

import org.example.service.ModelDeadline;
import org.springframework.ai.chat.messages.UserMessage;
import java.util.*;

final class PlanExecuteReplanExecutor implements AgentExecution.Executor {
    public String run(AgentExecution e){
        PlannedExecution.Outcome outcome;
        try(var deadline=ModelDeadline.bind(e.exploreUntil)) {
            e.prepareEvidence();
            // Open-ended questions do not need a planning loop after the shared initial lookup.
            if(e.context.openQuestion){
                e.complete=true;
                return e.finish();
            }
            var initial=Map.of("conversation",e.messages.stream().filter(m->!(m instanceof org.springframework.ai.chat.messages.SystemMessage)).map(m->Map.of("role",m.getMessageType().getValue(),"content",m.getText())).toList(),
                "evidence",List.copyOf(e.context.evidence.values()),"materials",e.context.materials.stream().map(m->Map.of("id",m.id(),"filename",m.filename(),"length",m.content().length())).toList(),
                "generalAllowed",e.context.generalAllowed,"openQuestion",e.context.openQuestion,"toolCallsUsed",e.calls.get(),"searches",e.context.searches);
            outcome=new PlannedExecution(e.models,e.json).run(e.question,e.config,e.availableTools(),initial,e::call,e::exhausted,
                plan->{e.context.plan=plan;e.context.emit.accept("plan",plan);},e.context.emit,e.check);
        }catch(RuntimeException error){throw error;}catch(Exception error){throw new IllegalStateException(error);}
        e.complete=Set.of("task_satisfied","ready_to_answer").contains(outcome.reason());
        e.notices.add("计划结束："+stopLabel(outcome.reason()));
        e.messages.add(new UserMessage("计划已停止："+outcome.reason()+"。task_satisfied表示取得所需信息；ready_to_answer表示无需继续工具调查，可以按通识权限回答或如实说明缺口，不代表取证成功。其他情况说明未完成部分。"));
        return e.finish();
    }
    static String stopLabel(String reason){return switch(reason){
        case "task_satisfied" -> "已取得回答所需证据";
        case "ready_to_answer" -> "已结束必要调查，进入回答";
        case "exploration_budget" -> "调查预算已用完，整理已有证据";
        case "planning_timeout" -> "规划调用超时，整理已有证据";
        case "repeated_request" -> "相同请求不再重复执行";
        case "no_progress" -> "连续调查没有新进展";
        case "replan_limit" -> "已达到两次重规划上限";
        case "invalid_plan" -> "计划格式修正失败，停止调查";
        case "blocked_dependency","blocked_step" -> "前置步骤未完成";
        case "step_attempt_limit" -> "当前目标已尝试三次，仍未完成";
        case "plan_exhausted_without_completion" -> "步骤已执行，但任务尚未确认完成";
        case "no_tools" -> "当前没有可用工具";
        default -> reason.startsWith("needs_input: ")?"需要补充信息："+reason.substring(13):"调查已停止";
    };}
}
