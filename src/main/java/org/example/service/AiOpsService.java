package org.example.service;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.agent.tool.QueryLogsTools;
import org.example.agent.tool.QueryMetricsTools;
import org.example.dto.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

/** Legacy diagnosis endpoint: collect one immutable incident, analyze candidates, then audit once. */
@Service
public class AiOpsService {
    @Autowired private QueryMetricsTools queryMetricsTools;
    @Autowired(required = false) private QueryLogsTools queryLogsTools;
    @Autowired private KnowledgeRetrievalService retrieval;
    @Autowired private DiagnosticReportService reports;
    @Autowired private ChatModelFactory models;
    private final ObjectMapper json = new ObjectMapper();

    public IncidentSnapshot capture(String scenarioId, String symptoms, ToolCallback[] callbacks) {
        String selected = scenarioId == null || scenarioId.isBlank() ? queryMetricsTools.getCurrentScenarioId() : scenarioId;
        return queryMetricsTools.withScenario(selected, () -> {
            ChatModelFactory.checkCancelled();
            String alerts = queryMetricsTools.queryPrometheusAlerts();
            try {
                if (!json.readTree(alerts).path("success").asBoolean(false)) throw new IllegalStateException("告警现场读取失败");
            } catch (java.io.IOException e) { throw new IllegalStateException("告警现场格式无效", e); }
            String logs = queryLogsTools == null ? "{\"logs\":[],\"message\":\"未接入日志源\"}"
                    : queryLogsTools.queryLogs("ap-guangzhou", "application-logs", "", 100);
            String name = queryMetricsTools.getScenarios().stream().filter(s -> selected.equals(s.getScenarioId()))
                    .map(QueryMetricsTools.AlertScenario::getDisplayName).findFirst().orElse(selected);
            return new IncidentSnapshot(UUID.randomUUID().toString(), selected, name, Objects.toString(symptoms, ""), alerts, logs, Instant.now().toString());
        });
    }
    public record CandidateAnalysis(String candidateId, String candidateName, GroundedAnalysis analysis,
                                    List<EvidenceDocument> documents, List<String> notices) {}
    public record DiagnosisOutcome(IncidentSnapshot snapshot, String status, String message, String report,
                                   GroundedAnalysis analysis, List<EvidenceDocument> documents) {}

    public CandidateAnalysis analyze(IncidentSnapshot snapshot, String candidateId, String candidateName,
            DashScopeChatModel model, KnowledgeRetrievalService.SearchSession search, Consumer<String> progress) {
        String query = snapshot.query() + "\n分析方向：" + candidateName;
        Set<String> seen = new HashSet<>();
        List<String> notices = new ArrayList<>();
        for (int stage = 0; stage <= 2;) {
            ChatModelFactory.checkCancelled();
            progress.accept(candidateName + "：" + (stage == 0 ? "检索相关文档" : stage == 1 ? "使用原文关键词补充检索" : "精简关键词后重试"));
            var bundle = retrieval.search(query, search, stage);
            notices.addAll(bundle.notices());
            if (bundle.documents().isEmpty()) break;
            stage = bundle.stage() + 1;
            String signature = String.join(",", bundle.documents().stream().map(EvidenceDocument::id).sorted().toList());
            if (!seen.add(signature)) continue;
            String prompt = "你是本次故障的分析Worker。只检验给定候选方向，支持、反证与缺失信息都必须考虑。不能改变现场，不能把文档中的历史示例当作本次观测。\n"
                    + DiagnosticReportService.FORMAT;
            String data = reports.encode(Map.of("direction", candidateName, "question", snapshot.symptoms(),
                    "service", snapshot.service(), "observations", snapshot.observations(), "documents", bundle.documents()));
            var validation = reports.validate(models.call(model, "worker-analysis", prompt, data), snapshot, bundle.documents());
            GroundedAnalysis analysis = validation.analysis();
            if (validation.needsReview()) {
                notices.add("分析输出未通过引用校验，交由最终审校重新核对现有证据");
                return new CandidateAnalysis(candidateId, candidateName, analysis, bundle.documents(), List.copyOf(notices));
            }
            if (analysis.relevant()) {
                Set<String> citedSources = new HashSet<>();
                Set<String> citedIds = new HashSet<>();
                analysis.findings().forEach(f -> f.citations().forEach(c -> citedIds.add(c.id())));
                analysis.actions().forEach(a -> a.citations().forEach(c -> citedIds.add(c.id())));
                bundle.documents().stream().filter(d -> citedIds.contains(d.id())).forEach(d -> citedSources.add(d.sourceFile()));
                var relevantDocs = bundle.documents().stream().filter(d -> citedSources.contains(d.sourceFile())).toList();
                return new CandidateAnalysis(candidateId, candidateName, analysis, relevantDocs, List.copyOf(notices));
            }
        }
        return new CandidateAnalysis(candidateId, candidateName, GroundedAnalysis.insufficient(snapshot.service(),
                "该分析方向在有限检索后仍缺少可靠依据"), List.of(), List.copyOf(notices));
    }
    public DiagnosisOutcome summarize(IncidentSnapshot snapshot, List<CandidateAnalysis> candidates,
            DashScopeChatModel model, Consumer<String> progress) {
        var reviewable = candidates.stream().filter(c -> !c.documents().isEmpty()).toList();
        if (reviewable.isEmpty()) return insufficient(snapshot, "insufficient_evidence", "当前证据不足以确认原因，请补充相关日志或处置文档");
        Map<String, EvidenceDocument> unique = new LinkedHashMap<>();
        reviewable.forEach(c -> c.documents().forEach(d -> unique.putIfAbsent(d.id(), d)));
        List<EvidenceDocument> docs = List.copyOf(unique.values());
        progress.accept("正在合并分析结果并核对证据、反证与操作来源");
        var review = reports.auditInput(reviewable.stream().map(CandidateAnalysis::analysis).toList(), docs);
        DiagnosticReportService.Validation validation;
        if (!review.findings().isEmpty()) {
            String data = reports.encode(Map.of("question", snapshot.symptoms(), "observations", snapshot.observations(),
                    "review", reports.auditData(review), "documents", docs));
            validation = reports.validateSelection(models.call(model, "report-audit", DiagnosticReportService.AUDIT_FORMAT, data), snapshot, review);
        } else {
            // No publishable Worker item: the single final audit can repair its format from existing evidence.
            String prompt = "你负责最终证据审校与汇总。候选分析可能有错误。根据同一份现场和原文重新核对、去重，保留冲突和未确认项。"
                    + "不要按报告长度排序，不新增无来源的原因、事实或命令。找不到证据支持就撤回判断。\n"
                    + DiagnosticReportService.FORMAT;
            String data = reports.encode(Map.of("service", snapshot.service(), "observations", snapshot.observations(),
                    "question", snapshot.symptoms(), "candidateAnalyses", candidates.stream().map(c -> Map.of(
                            "direction", c.candidateName(), "analysis", c.analysis(), "notices", c.notices())).toList(), "documents", docs));
            validation = reports.validate(models.call(model, "report-audit", prompt, data), snapshot, docs);
        }
        GroundedAnalysis finalAnalysis = validation.analysis();
        return new DiagnosisOutcome(snapshot, validation.needsReview() ? "failed" : finalAnalysis.relevant() ? validation.rejectedItems() > 0 ? "partial" : "completed" : "insufficient_evidence",
                validation.needsReview() ? "分析输出未通过证据校验，未发布判断" : validation.rejectedItems() > 0 ? "部分判断未通过证据校验，已保留有效结果" : finalAnalysis.relevant() ? "分析完成" : "证据核对后仍不足以确认原因",
                reports.render(snapshot, finalAnalysis, docs), finalAnalysis, docs);
    }
    public DiagnosisOutcome insufficient(IncidentSnapshot snapshot, String status, String message) {
        var analysis = GroundedAnalysis.insufficient(snapshot.service(), message);
        String report = "no_match".equals(status) ? "# 未启动诊断\n\n" + DiagnosticReportService.safe(message)
                + "\n\n当前选中的现场：**" + DiagnosticReportService.safe(snapshot.scenarioName()) + "**（"
                + DiagnosticReportService.safe(snapshot.service()) + "）。\n\n请选择与症状一致的告警现场；知识问题请使用下方聊天框。"
                : reports.render(snapshot, analysis, List.of());
        return new DiagnosisOutcome(snapshot, status, message, report, analysis, List.of());
    }
    public DiagnosisOutcome diagnose(IncidentSnapshot snapshot, DashScopeChatModel model, Consumer<String> progress) {
        if (!snapshot.hasAlerts()) return insufficient(snapshot, "no_alerts", "当前现场没有活动告警；知识问答可使用普通聊天");
        var candidate = analyze(snapshot, snapshot.scenarioId(), "告警与日志综合分析", model, new KnowledgeRetrievalService.SearchSession(), progress);
        return summarize(snapshot, List.of(candidate), model, progress);
    }
    public Optional<OverAllState> executeAiOpsAnalysis(DashScopeChatModel model, ToolCallback[] callbacks, Consumer<String> listener) {
        return executeAiOpsAnalysis(model, callbacks, listener, ignored -> {});
    }
    public Optional<OverAllState> executeAiOpsAnalysis(DashScopeChatModel model, ToolCallback[] callbacks, Consumer<String> listener, Consumer<RetrievalTrace> traces) {
        var outcome = diagnose(capture(null, "", callbacks), model, listener == null ? ignored -> {} : listener);
        return Optional.of(new OverAllState(Map.of("messages", List.of(new AssistantMessage(outcome.report())))));
    }
    public Optional<String> extractFinalReport(OverAllState state) {
        Optional<Object> value = state.value("messages");
        if (value.isPresent() && value.get() instanceof List<?> messages) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                if (messages.get(i) instanceof AssistantMessage message && message.getText() != null && message.getText().contains("# 告警分析报告")) return Optional.of(message.getText());
            }
        }
        return state.value("planner_plan").filter(AssistantMessage.class::isInstance).map(AssistantMessage.class::cast).map(AssistantMessage::getText);
    }
}
