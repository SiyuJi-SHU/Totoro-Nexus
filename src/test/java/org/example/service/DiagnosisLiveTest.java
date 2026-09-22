package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.GroundedAnalysis;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;

/** Explicitly enabled local acceptance. Uses configured APIs; raw evidence stays in target. */
@SpringBootTest(classes = org.example.Main.class, webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"milvus.host=localhost", "logging.level.org.example.service.DocumentChunkService=WARN"})
@EnabledIfSystemProperty(named = "oncall.live", matches = "true")
@Timeout(180)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DiagnosisLiveTest {
    @Autowired AiOpsService aiOps;
    @Autowired ChatModelFactory models;
    @Autowired SmartAiOpsService smart;
    @Autowired org.example.agent.SupervisorAgent supervisor;
    @Autowired io.micrometer.core.instrument.MeterRegistry meters;
    @SpyBean DiagnosticReportService reports;
    private final ObjectMapper json = new ObjectMapper();
    private Path output;

    @BeforeEach void captureModelValidation(TestInfo test) throws Exception {
        output = Path.of("target", "live-diagnosis", test.getTestMethod().orElseThrow().getName());
        Files.createDirectories(output);
        AtomicInteger sequence = new AtomicInteger();
        org.mockito.stubbing.Answer<DiagnosticReportService.Validation> capture = call -> {
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("response", call.getArgument(0));
            evidence.put("snapshot", call.getArgument(1));
            evidence.put("documents", call.getArgument(2));
            evidence.put("validationType", call.getMethod().getName());
            try {
                DiagnosticReportService.Validation result = (DiagnosticReportService.Validation) call.callRealMethod();
                evidence.put("validated", result.analysis());
                evidence.put("needsReview", result.needsReview());
                return result;
            } catch (RuntimeException ex) {
                evidence.put("validationError", ex.toString());
                throw ex;
            } finally {
                json.writerWithDefaultPrettyPrinter().writeValue(
                        output.resolve("validation-" + sequence.incrementAndGet() + ".json").toFile(), evidence);
            }
        };
        doAnswer(capture).when(reports).validate(anyString(), any(), anyList());
        doAnswer(capture).when(reports).validateSelection(anyString(), any(), any(DiagnosticReportService.AuditInput.class));
    }

    @Test @Order(2) void knownPostgresEvidenceProducesReport() throws Exception {
        var snapshot = aiOps.capture("postgres_data_corruption_001",
                "PostgreSQL 日志出现 invalid page 和 checksum verification failed，数据库读取失败", null);
        var outcome = run(snapshot, false);
        json.writerWithDefaultPrettyPrinter().writeValue(output.resolve("postgres-outcome.json").toFile(), outcome);
        assertThat(outcome.status()).isEqualTo("completed");
        assertThat(outcome.analysis().service()).isEqualTo(snapshot.service());
        assertThat(outcome.analysis().findings()).isNotEmpty();
        assertThat(outcome.analysis().contradictions()).isEmpty();
        assertNoUnobservedConfiguration(outcome.analysis());
        assertThat(outcome.documents()).anySatisfy(doc ->
                assertThat(doc.sourceFile()).contains("postgres-data-corruption"));
    }

    @Test @Order(1) void presetSidekiqProducesGroundedDiagnosis() throws Exception {
        var snapshot = aiOps.capture("sidekiq_error_rate_high_001", "", null);
        var outcome = run(snapshot, false);
        json.writerWithDefaultPrettyPrinter().writeValue(output.resolve("sidekiq-outcome.json").toFile(), outcome);
        assertThat(outcome.status()).isEqualTo("completed");
        assertThat(outcome.analysis().service()).isEqualTo("sidekiq-reactive-cache");
        assertThat(outcome.analysis().findings()).anySatisfy(finding ->
                assertThat(finding.text()).containsAnyOf("EHOSTUNREACH", "不可达", "连接失败", "网络"));
        assertThat(outcome.documents()).anySatisfy(doc ->
                assertThat(doc.sourceFile()).contains("sidekiq_error_rate_high"));
        assertThat(outcome.analysis().actions()).isNotEmpty();
    }

    @ParameterizedTest @Order(4)
    @ValueSource(strings = {"apdex_slo_violation_001", "error_slo_violation_001", "traffic_absent_001",
            "blackbox_probe_failures_001", "cloud_sql_database_down_001", "gitaly_repository_corruption_001",
            "elk_mapper_parsing_exception_001", "kube_containers_waiting_in_error_001",
            "patroni_deadlocks_detected_001", "postgresql_disk_space_001"})
    void remainingPresetScenariosProduceEvidenceBasedReports(String scenario) throws Exception {
        var outcome = run(aiOps.capture(scenario, "", null), false);
        assertThat(outcome.status()).isEqualTo("completed");
        assertThat(outcome.analysis().findings()).isNotEmpty();
        assertThat(outcome.analysis().service()).isEqualTo(outcome.snapshot().service());
        assertThat(outcome.analysis().findings().stream().flatMap(f -> f.citations().stream()))
                .anySatisfy(c -> assertThat(c.id().startsWith("L") || c.id().startsWith("A")).isTrue());
        assertThat(outcome.documents()).isNotEmpty();
    }

    @ParameterizedTest @Order(5)
    @ValueSource(strings = {"请介绍一下这个系统的功能，不要诊断告警", "帮我写一封请假邮件",
            "今天上海天气怎么样", "我用的Redis报OOM，帮我检查Redis内存问题"})
    void irrelevantQuestionsCannotBorrowTheSelectedAlert(String question) {
        var incident = aiOps.capture("postgres_data_corruption_001", question, null);
        assertThat(supervisor.identifyIncidentDirections(incident, models.diagnosis())).isEmpty();
    }

    @Test @Order(3) void smartDiagnosisUsesTheSelectedIncident() throws Exception {
        var outcome = run(aiOps.capture("postgres_data_corruption_001",
                "不要分析其他服务，只诊断这次PostgreSQL invalid page 和 checksum verification failed", null), true);
        assertThat(outcome.status()).isEqualTo("completed");
        assertThat(outcome.analysis().service()).isEqualTo("postgresql-main");
        assertThat(outcome.analysis().findings()).isNotEmpty();
        // ERROR/WARN records are not positive evidence excluding disk faults.
        assertThat(outcome.analysis().contradictions()).isEmpty();
        assertNoUnobservedConfiguration(outcome.analysis());
    }

    private void assertNoUnobservedConfiguration(GroundedAnalysis analysis) {
        assertThat(analysis.findings()).noneMatch(f -> f.text().matches(
                "(?is).*(?:checksum|校验开关|校验功能).*(?:已启用|已开启|确定开启|确认启用).*"));
    }

    private AiOpsService.DiagnosisOutcome run(org.example.dto.IncidentSnapshot snapshot, boolean useSmart) throws Exception {
        output = output.resolve(snapshot.scenarioId());
        Files.createDirectories(output);
        var progress = java.util.Collections.synchronizedList(new java.util.ArrayList<String>());
        long inputBefore = tokens("input"), outputBefore = tokens("output");
        long start = System.nanoTime();
        var outcome = useSmart ? smart.diagnose(snapshot, models.diagnosis(), progress::add)
                : aiOps.diagnose(snapshot, models.diagnosis(), progress::add);
        long elapsed = (System.nanoTime() - start) / 1_000_000;
        json.writerWithDefaultPrettyPrinter().writeValue(output.resolve("outcome.json").toFile(), outcome);
        json.writerWithDefaultPrettyPrinter().writeValue(output.resolve("execution.json").toFile(),
                Map.of("latencyMs", elapsed, "progress", progress, "status", outcome.status(),
                        "inputTokens", tokens("input") - inputBefore, "outputTokens", tokens("output") - outputBefore,
                        "contextChars", outcome.documents().stream().mapToInt(d -> d.content().length()).sum()));
        System.out.println("LIVE_ACCEPTANCE " + snapshot.scenarioId() + " " + outcome.status() + " " + elapsed + "ms");
        assertThat(elapsed).isLessThan(115000);
        return outcome;
    }
    private long tokens(String type) {
        return Math.round(meters.find("gen_ai.client.token.usage").tag("gen_ai.token.type", type).counters()
                .stream().mapToDouble(io.micrometer.core.instrument.Counter::count).sum());
    }
}
