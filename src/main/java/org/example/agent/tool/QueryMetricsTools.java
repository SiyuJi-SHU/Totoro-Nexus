package org.example.agent.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Prometheus 告警查询工具
 * 用于查询 Prometheus 的活动告警信息
 */
@Component
public class QueryMetricsTools {

    private static final Logger logger = LoggerFactory.getLogger(QueryMetricsTools.class);
    
    /** 工具名常量，用于动态构建提示词 */
    public static final String TOOL_QUERY_PROMETHEUS_ALERTS = "queryPrometheusAlerts";
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Value("${prometheus.base-url}")
    private String prometheusBaseUrl;
    
    @Value("${prometheus.timeout:10}")
    private int timeout;
    
    @Value("${prometheus.mock-enabled:false}")
    private boolean mockEnabled;

    @Value("${aiops.scenario.file:classpath:aiops-scenarios/alert_scenarios_cn.json}")
    private Resource scenarioResource;

    @Value("${aiops.scenario.current:apdex_slo_violation_001}")
    private volatile String currentScenarioId;
    private final ThreadLocal<String> scopedScenarioId = new ThreadLocal<>();

    private List<AlertScenario> scenarios = Collections.emptyList();
    
    private OkHttpClient httpClient;
    
    @jakarta.annotation.PostConstruct
    public void init() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(timeout))
                .readTimeout(Duration.ofSeconds(timeout))
                .build();

        if (mockEnabled) {
            loadScenarios();
        }

        logger.info("✅ QueryMetricsTools 初始化成功, Prometheus URL: {}, Mock模式: {}", prometheusBaseUrl, mockEnabled);
    }

    private void loadScenarios() {
        try {
            String json = new String(scenarioResource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            List<AlertScenario> loadedScenarios = objectMapper.readValue(
                    json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, AlertScenario.class));

            if (loadedScenarios.isEmpty()) {
                throw new IllegalStateException("告警场景文件为空");
            }

            this.scenarios = List.copyOf(loadedScenarios);
            if (findScenario(currentScenarioId).isEmpty()) {
                logger.warn("配置的默认场景不存在: {}, 将使用第一个场景", currentScenarioId);
                this.currentScenarioId = this.scenarios.get(0).getScenarioId();
            }

            logger.info("✅ 已加载 {} 个告警场景，当前场景: {}", scenarios.size(), currentScenarioId);
        } catch (Exception e) {
            logger.error("❌ 加载告警场景失败，将使用兜底 Mock 告警", e);
            this.scenarios = Collections.emptyList();
        }
    }
    
    /**
     * 查询 Prometheus 活动告警
     * 该工具从 Prometheus 告警系统检索所有当前活动/触发的告警，包括标签、注释、状态和值
     */
    @Tool(description = "Query active alerts from Prometheus alerting system. " +
            "This tool retrieves all currently active/firing alerts including their labels, annotations, state, and values. " +
            "Use this tool when you need to check what alerts are currently firing, investigate alert conditions, or monitor alert status.")
    public String queryPrometheusAlerts() {
        logger.info("开始查询 Prometheus 活动告警, Mock模式: {}", mockEnabled);
        
        try {
            List<SimplifiedAlert> simplifiedAlerts;
            
            if (mockEnabled) {
                // Mock 模式：返回与文档关联的模拟告警数据
                simplifiedAlerts = buildMockAlerts();
                logger.info("使用 Mock 数据，返回 {} 个模拟告警", simplifiedAlerts.size());
            } else {
                // 真实模式：调用 Prometheus Alerts API
                PrometheusAlertsResult result = fetchPrometheusAlerts();
                
                if (!"success".equals(result.getStatus())) {
                    return buildErrorResponse("Prometheus API 返回非成功状态: " + result.getStatus(), result.getError());
                }
                
                // 转换为简化格式，对于相同的 alertname，只保留第一个
                Set<String> seenAlertNames = new HashSet<>();
                simplifiedAlerts = new ArrayList<>();
                
                for (PrometheusAlert alert : result.getData().getAlerts()) {
                    String alertName = alert.getLabels().get("alertname");
                    
                    // 如果这个 alertname 已经存在，跳过
                    if (seenAlertNames.contains(alertName)) {
                        continue;
                    }
                    
                    // 标记为已见过
                    seenAlertNames.add(alertName);
                    
                    SimplifiedAlert simplified = new SimplifiedAlert();
                    simplified.setAlertName(alertName);
                    simplified.setDescription(alert.getAnnotations().getOrDefault("description", ""));
                    simplified.setState(alert.getState());
                    simplified.setActiveAt(alert.getActiveAt());
                    simplified.setDuration(calculateDuration(alert.getActiveAt()));
                    
                    simplifiedAlerts.add(simplified);
                }
            }
            
            // 构建成功响应
            PrometheusAlertsOutput output = new PrometheusAlertsOutput();
            output.setSuccess(true);
            output.setAlerts(simplifiedAlerts);
            output.setMessage(String.format("成功检索到 %d 个活动告警", simplifiedAlerts.size()));
            
            String jsonResult = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(output);
            logger.info("Prometheus 告警查询完成: 找到 {} 个告警", simplifiedAlerts.size());
            
            return jsonResult;
            
        } catch (Exception e) {
            logger.error("查询 Prometheus 告警失败", e);
            return buildErrorResponse("查询失败", e.getMessage());
        }
    }
    
    private List<SimplifiedAlert> buildMockAlerts() {
        Optional<AlertScenario> currentScenario = findScenario(getCurrentScenarioId());
        if (currentScenario.isEmpty()) {
            throw new IllegalStateException("告警场景未加载或不存在，未生成替代现场");
        }

        AlertScenario scenario = currentScenario.get();
        AlertInfo source = scenario.getAlert();
        SimplifiedAlert alert = new SimplifiedAlert();
        alert.setAlertName(source.getAlertName());
        alert.setDescription(source.getDescription());
        alert.setState("firing");
        alert.setActiveAt(source.getActiveAt() == null || source.getActiveAt().isBlank()
                ? calculateActiveAt(source.getDuration()).toString() : source.getActiveAt());
        alert.setDuration(source.getDuration());
        alert.setSeverity(source.getSeverity());
        alert.setService(source.getService());
        alert.setCurrentValue(source.getCurrentValue());
        alert.setThreshold(source.getThreshold());
        alert.setInstance(source.getInstance());
        alert.setEnvironment(source.getEnvironment());
        alert.setDataSource(source.getDataSource());
        alert.setObservedAt(source.getObservedAt());
        alert.setImpact(source.getImpact());

        return List.of(alert);
    }

    private Optional<AlertScenario> findScenario(String scenarioId) {
        if (scenarioId == null || scenarios.isEmpty()) {
            return Optional.empty();
        }
        return scenarios.stream()
                .filter(scenario -> scenarioId.equals(scenario.getScenarioId()))
                .findFirst();
    }

    private Instant calculateActiveAt(String duration) {
        if (duration == null || duration.isBlank()) {
            return Instant.now();
        }

        try {
            return Instant.now().minus(Duration.parse("PT" + duration.toUpperCase(Locale.ROOT)));
        } catch (Exception e) {
            logger.warn("无法解析场景持续时间: {}, 将使用当前时间", duration);
            return Instant.now();
        }
    }



    public List<AlertScenario> getScenarios() {
        return scenarios;
    }

    public String getCurrentScenarioId() {
        return scopedScenarioId.get() == null ? currentScenarioId : scopedScenarioId.get();
    }

    /** Isolate a complete synchronous diagnosis from other requests and UI selections. */
    public <T> T withScenario(String scenarioId, java.util.function.Supplier<T> action) {
        if (findScenario(scenarioId).isEmpty()) throw new IllegalArgumentException("未知告警场景: " + scenarioId);
        String previous = scopedScenarioId.get();
        scopedScenarioId.set(scenarioId);
        try { return action.get(); }
        finally {
            if (previous == null) scopedScenarioId.remove();
            else scopedScenarioId.set(previous);
        }
    }

    public void setCurrentScenario(String scenarioId) {
        if (findScenario(scenarioId).isEmpty()) {
            throw new IllegalArgumentException("未知告警场景: " + scenarioId);
        }
        this.currentScenarioId = scenarioId;
        logger.info("🔄 切换到场景: {}", scenarioId);
    }
    
    /**
     * 从 Prometheus API 获取告警数据
     */
    private PrometheusAlertsResult fetchPrometheusAlerts() throws Exception {
        String apiUrl = prometheusBaseUrl + "/api/v1/alerts";
        logger.debug("请求 Prometheus API: {}", apiUrl);
        
        Request request = new Request.Builder()
                .url(apiUrl)
                .get()
                .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new RuntimeException("HTTP 请求失败: " + response.code());
            }
            
            String responseBody = response.body().string();
            return objectMapper.readValue(responseBody, PrometheusAlertsResult.class);
        }
    }
    
    /**
     * 计算从 activeAt 到现在的持续时间
     */
    private String calculateDuration(String activeAtStr) {
        try {
            Instant activeAt = Instant.parse(activeAtStr);
            Duration duration = Duration.between(activeAt, Instant.now());
            
            long hours = duration.toHours();
            long minutes = duration.toMinutes() % 60;
            long seconds = duration.getSeconds() % 60;
            
            if (hours > 0) {
                return String.format("%dh%dm%ds", hours, minutes, seconds);
            } else if (minutes > 0) {
                return String.format("%dm%ds", minutes, seconds);
            } else {
                return String.format("%ds", seconds);
            }
        } catch (Exception e) {
            logger.warn("解析时间失败: {}", activeAtStr, e);
            return "unknown";
        }
    }
    
    /**
     * 构建错误响应
     */
    private String buildErrorResponse(String message, String error) {
        try {
            PrometheusAlertsOutput output = new PrometheusAlertsOutput();
            output.setSuccess(false);
            output.setMessage(message);
            output.setError(error);
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(output);
        } catch (Exception e) {
            return String.format("{\"success\":false,\"message\":\"%s\",\"error\":\"%s\"}", message, error);
        }
    }
    
    // ==================== 数据模型 ====================
    
    /**
     * Prometheus 告警信息结构
     */
    @Data
    public static class PrometheusAlert {
        private Map<String, String> labels;
        private Map<String, String> annotations;
        private String state;
        private String activeAt;
        private String value;
    }
    
    /**
     * Prometheus 告警查询结果
     */
    @Data
    public static class PrometheusAlertsResult {
        private String status;
        private AlertsData data;
        private String error;
        private String errorType;
    }
    
    @Data
    public static class AlertsData {
        private List<PrometheusAlert> alerts = new ArrayList<>();
    }
    
    /**
     * 简化的告警信息
     */
    @Data
    public static class SimplifiedAlert {
        @JsonProperty("severity")
        private String severity;

        @JsonProperty("service")
        private String service;

        @JsonProperty("current_value")
        private String currentValue;

        @JsonProperty("threshold")
        private String threshold;

        @JsonProperty("instance")
        private String instance;

        @JsonProperty("environment")
        private String environment;

        @JsonProperty("data_source")
        private String dataSource;

        @JsonProperty("observed_at")
        private String observedAt;

        @JsonProperty("impact")
        private String impact;

        @JsonProperty("alert_name")
        private String alertName;
        
        @JsonProperty("description")
        private String description;
        
        @JsonProperty("state")
        private String state;
        
        @JsonProperty("active_at")
        private String activeAt;
        
        @JsonProperty("duration")
        private String duration;
    }

    @Data
    public static class AlertScenario {
        @JsonProperty("scenario_id")
        private String scenarioId;

        @JsonProperty("display_name")
        private String displayName;

        @JsonProperty("alert")
        private AlertInfo alert;

        @JsonProperty("related_logs")
        private List<LogEntry> relatedLogs = new ArrayList<>();

        @JsonProperty("expected_docs")
        private List<String> expectedDocs = new ArrayList<>();

        @JsonProperty("expected_root_cause")
        private String expectedRootCause;

        @JsonProperty("expected_actions")
        private List<String> expectedActions = new ArrayList<>();
    }

    @Data
    public static class AlertInfo {
        @JsonProperty("instance")
        private String instance;

        @JsonProperty("environment")
        private String environment;

        @JsonProperty("data_source")
        private String dataSource;

        @JsonProperty("observed_at")
        private String observedAt;

        @JsonProperty("impact")
        private String impact;

        @JsonProperty("active_at")
        private String activeAt;

        @JsonProperty("alert_name")
        private String alertName;

        @JsonProperty("description")
        private String description;

        @JsonProperty("severity")
        private String severity;

        @JsonProperty("service")
        private String service;

        @JsonProperty("duration")
        private String duration;

        @JsonProperty("current_value")
        private String currentValue;

        @JsonProperty("threshold")
        private String threshold;
    }

    @Data
    public static class LogEntry {
        @JsonProperty("instance")
        private String instance;

        @JsonProperty("timestamp")
        private String timestamp;

        @JsonProperty("level")
        private String level;

        @JsonProperty("service")
        private String service;

        @JsonProperty("message")
        private String message;
    }
    
    /**
     * 告警查询输出
     */
    @Data
    public static class PrometheusAlertsOutput {
        @JsonProperty("success")
        private boolean success;
        
        @JsonProperty("alerts")
        private List<SimplifiedAlert> alerts;
        
        @JsonProperty("message")
        private String message;
        
        @JsonProperty("error")
        private String error;
    }
}
