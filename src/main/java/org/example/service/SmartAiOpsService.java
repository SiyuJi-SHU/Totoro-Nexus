package org.example.service;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import org.example.agent.SupervisorAgent;
import org.example.agent.tool.QueryMetricsTools;
import org.example.dto.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.tool.ToolCallback;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** One incident, up to three candidate analyses, and one final evidence-checked report. */
@Service
public class SmartAiOpsService {
    private final SupervisorAgent supervisor;
    private final AiOpsService aiOps;
    private final ExecutorService workers = new ThreadPoolExecutor(6, 6, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(24), runnable -> { Thread t = new Thread(runnable, "diagnosis-worker"); t.setDaemon(true); return t; }, new ThreadPoolExecutor.AbortPolicy());
    @Value("${aiops.smart.timeout-ms:120000}") private long timeoutMs = 120000;
    public SmartAiOpsService(SupervisorAgent supervisor, AiOpsService aiOps, QueryMetricsTools ignored) { this.supervisor = supervisor; this.aiOps = aiOps; }

    public AiOpsService.DiagnosisOutcome diagnose(IncidentSnapshot snapshot, DashScopeChatModel model, Consumer<String> listener) {
        if (explicitNonDiagnostic(snapshot.symptoms())) return aiOps.insufficient(snapshot, "no_match", "该输入没有请求诊断当前故障");
        if (!snapshot.hasAlerts()) return aiOps.insufficient(snapshot, "no_alerts", "当前没有活动告警；可在普通聊天中查询知识库");
        Consumer<String> progress = message -> { ChatModelFactory.checkCancelled(); if (listener != null) listener.accept(message); };
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        List<Future<?>> submitted = new ArrayList<>();
        try {
            progress.accept("正在根据本次现场识别相关分析方向");
            Future<List<ScenarioMatch>> selection = workers.submit(() -> supervisor.identifyIncidentDirections(snapshot, model));
            submitted.add(selection);
            List<ScenarioMatch> matches = selection.get(remaining(deadline), TimeUnit.NANOSECONDS).stream()
                    .filter(m -> m != null && Double.isFinite(m.getConfidence()) && m.getConfidence() >= .5 && m.getConfidence() <= 1).limit(3).toList();
            if (matches.isEmpty()) return aiOps.insufficient(snapshot, "no_match", "没有与问题和现场相关的分析方向，请补充服务名或错误信息");
            progress.accept("已选择 " + matches.size() + " 个分析方向，共用同一份告警和日志");
            var search = new KnowledgeRetrievalService.SearchSession();
            List<Future<AiOpsService.CandidateAnalysis>> futures = new ArrayList<>();
            for (var match : matches) {
                var task = workers.submit(() -> aiOps.analyze(snapshot, match.getScenarioId(), match.getScenarioName(), model, search, progress));
                futures.add(task); submitted.add(task);
            }
            List<AiOpsService.CandidateAnalysis> completed = new ArrayList<>();
            List<String> failures = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                try { completed.add(futures.get(i).get(remaining(deadline), TimeUnit.NANOSECONDS)); }
                catch (ExecutionException e) { failures.add(matches.get(i).getScenarioName() + "分析失败"); }
            }
            if (completed.isEmpty()) throw new IllegalStateException("所有候选分析均失败");
            var summary = workers.submit(() -> aiOps.summarize(snapshot, completed, model, progress)); submitted.add(summary);
            var result = summary.get(remaining(deadline), TimeUnit.NANOSECONDS);
            if (failures.isEmpty()) return result;
            return new AiOpsService.DiagnosisOutcome(snapshot, "completed".equals(result.status()) ? "partial" : result.status(), String.join("；", failures),
                    result.report() + "\n\n> 部分分析未完成：" + String.join("；", failures), result.analysis(), result.documents());
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new CancellationException("诊断已取消"); }
        catch (TimeoutException e) { throw new IllegalStateException("诊断超过时间上限，请缩小问题范围后重试", e); }
        catch (ExecutionException e) { throw new IllegalStateException("分析方向识别或汇总失败，未生成替代场景", e.getCause()); }
        finally { submitted.forEach(f -> { if (!f.isDone()) f.cancel(true); }); }
    }
    static boolean explicitNonDiagnostic(String question) {
        if (question == null) return false;
        return java.util.regex.Pattern.compile("(?:不要|不需要|无需)(?:诊断|分析)(?:告警|故障)?[。！!\\s]*$").matcher(question).find()
                || question.matches("(?s).*(?:帮我写一封请假邮件|今天.{0,8}天气怎么样)[？?。\\s]*");
    }
    private static long remaining(long deadline) throws TimeoutException {
        long value = deadline - System.nanoTime(); if (value <= 0) throw new TimeoutException(); return value;
    }

    public SmartDiagnosisResult smartDiagnosis(String symptoms, DashScopeChatModel model, ToolCallback[] callbacks, Consumer<String> listener) {
        if (symptoms == null || symptoms.isBlank() || symptoms.length() > 4000) throw new IllegalArgumentException("请输入1–4000字的症状描述");
        return result(diagnose(aiOps.capture(null, symptoms, callbacks), model, listener));
    }
    public static SmartDiagnosisResult result(AiOpsService.DiagnosisOutcome outcome) {
        boolean hasPrimary = "completed".equals(outcome.status()) || "partial".equals(outcome.status());
        return SmartDiagnosisResult.builder().diagnosisId(outcome.snapshot().id()).status(outcome.status()).message(outcome.message())
                .report(outcome.report()).evidenceDocuments(outcome.documents())
                .primary(hasPrimary ? new ScoredDiagnosis(outcome.snapshot().scenarioId(), "综合诊断", 0, outcome.report()) : null)
                .secondary(List.of()).degraded(!"completed".equals(outcome.status())).build();
    }
    @PreDestroy public void close() { workers.shutdownNow(); }
}
