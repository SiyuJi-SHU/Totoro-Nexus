package org.example.agent.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 日志查询工具
 * 用于查询 CLS（云日志服务）的日志信息
 * 支持 Mock 模式，提供与告警关联的模拟日志数据
 */
@Component
public class QueryLogsTools {

    private static final Logger logger = LoggerFactory.getLogger(QueryLogsTools.class);
    
    /** 工具名常量，用于动态构建提示词 */
    public static final String TOOL_QUERY_LOGS = "queryLogs";
    public static final String TOOL_GET_AVAILABLE_LOG_TOPICS = "getAvailableLogTopics";
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Value("${cls.mock-enabled:false}")
    private boolean mockEnabled;

    @Autowired
    private QueryMetricsTools queryMetricsTools;
    
    
    @jakarta.annotation.PostConstruct
    public void init() {
        logger.info("✅ QueryLogsTools 初始化成功, Mock模式: {}", mockEnabled);
    }
    
    /**
     * 获取可用的日志主题列表
     * 用于查询前先了解有哪些日志主题可供查询
     */
    @Tool(description = "Get all available log topics and their descriptions. " +
            "Call this tool first before querying logs to understand what log topics are available. " +
            "Returns a list of log topics with their names, descriptions, and example queries.")
    public String getAvailableLogTopics() {
        if (mockEnabled) return "{\"success\":true,\"topics\":[{\"topicName\":\"application-logs\",\"region\":\"ap-guangzhou\",\"description\":\"固定模拟现场。支持正文片段或level:ERROR/service:名称/instance:名称精确过滤；不会产生新日志\"}]}";
        logger.info("获取可用的日志主题列表");
        
        try {
            List<LogTopicInfo> topics = new ArrayList<>();
            
            // 系统指标日志
            LogTopicInfo systemMetrics = new LogTopicInfo();
            systemMetrics.setTopicName("system-metrics");
            systemMetrics.setDescription("系统指标日志，包含 CPU、内存、磁盘使用率等系统资源监控数据");
            systemMetrics.setExampleQueries(List.of(
                    "cpu_usage:>80",
                    "memory_usage:>85",
                    "disk_usage:>90",
                    "level:WARN AND service:payment-service"
            ));
            systemMetrics.setRelatedAlerts(List.of("HighCPUUsage", "HighMemoryUsage", "HighDiskUsage"));
            topics.add(systemMetrics);
            
            // 应用日志
            LogTopicInfo applicationLogs = new LogTopicInfo();
            applicationLogs.setTopicName("application-logs");
            applicationLogs.setDescription("应用日志，包含应用程序的错误日志、警告日志、慢请求日志、下游依赖调用日志等");
            applicationLogs.setExampleQueries(List.of(
                    "level:ERROR",
                    "level:FATAL",
                    "http_status:500",
                    "response_time:>3000",
                    "slow",
                    "downstream OR redis OR database OR mq"
            ));
            applicationLogs.setRelatedAlerts(List.of("ServiceUnavailable", "SlowResponse", "HighMemoryUsage"));
            topics.add(applicationLogs);
            
            // 数据库慢查询日志
            LogTopicInfo dbSlowQuery = new LogTopicInfo();
            dbSlowQuery.setTopicName("database-slow-query");
            dbSlowQuery.setDescription("数据库慢查询日志，包含执行时间较长的 SQL 查询，可用于分析数据库性能问题");
            dbSlowQuery.setExampleQueries(List.of(
                    "query_time:>2",
                    "table:orders",
                    "query_type:SELECT",
                    "*"  // 查询所有慢查询
            ));
            dbSlowQuery.setRelatedAlerts(List.of("SlowResponse", "ServiceUnavailable"));
            topics.add(dbSlowQuery);
            
            // 系统事件日志
            LogTopicInfo systemEvents = new LogTopicInfo();
            systemEvents.setTopicName("system-events");
            systemEvents.setDescription("系统事件日志，包含 Kubernetes Pod 重启、OOM Kill、容器崩溃等系统级事件");
            systemEvents.setExampleQueries(List.of(
                    "restart OR crash",
                    "oom_kill",
                    "event_type:PodRestart",
                    "reason:OOMKilled"
            ));
            systemEvents.setRelatedAlerts(List.of("ServiceUnavailable", "HighMemoryUsage"));
            topics.add(systemEvents);
            
            // 构建输出
            LogTopicsOutput output = new LogTopicsOutput();
            output.setSuccess(true);
            output.setTopics(topics);
            output.setAvailableRegions(List.of("ap-guangzhou", "ap-shanghai", "ap-beijing", "ap-chengdu"));
            output.setDefaultRegion("ap-guangzhou");

            output.setMessage(String.format("共有 %d 个可用的日志主题。建议使用默认地域 'ap-guangzhou' 或省略 region 参数", topics.size()));
            
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(output);
            
        } catch (Exception e) {
            logger.error("获取日志主题列表失败", e);
            return "{\"success\":false,\"message\":\"获取日志主题列表失败: " + e.getMessage() + "\"}";
        }
    }
    
    /**
     * 查询日志
     * 从云日志服务查询指定条件的日志
     * 
     * @param region 地域，如 ap-guangzhou
     * @param logTopic 日志主题，如 system-metrics, application-logs
     * @param query 查询条件，如 level:ERROR OR cpu_usage:>80
     * @param limit 返回的日志条数，默认20条
     */
    // 有效地域列表
    private static final List<String> VALID_REGIONS = List.of(
            "ap-guangzhou", "ap-shanghai", "ap-beijing", "ap-chengdu"
    );

    private static final String DEFAULT_REGION = "ap-guangzhou";
    
    @Tool(description = "Read logs from the selected fixed mock incident. Call getAvailableLogTopics for supported topics. Supports a literal message substring or one exact level/service/instance field filter. Empty query or * returns the existing snapshot, never fresh telemetry. Not a full Lucene query engine.")
    public String queryLogs(
            @ToolParam(description = "地域，可选值: ap-guangzhou, ap-shanghai, ap-beijing, ap-chengdu。默认 ap-guangzhou") String region,
            @ToolParam(description = "日志主题，如 system-metrics, application-logs, database-slow-query, system-events，也支持 CLS TopicId") String logTopic,
            @ToolParam(description = "正文片段，或单个level:ERROR/service:名称/instance:名称字段；空或*返回固定现场") String query,
            @ToolParam(description = "返回日志条数，默认20，最大100") Integer limit) {
        
        int actualLimit = (limit == null || limit <= 0) ? 20 : Math.min(limit, 100);
        
        String safeQuery = query == null ? "" : query;
        

        try {
            List<LogEntry> logEntries;
            
            if (mockEnabled) {
                if (region!=null&&!region.isBlank()&&!region.equals(DEFAULT_REGION)) return buildErrorResponse("模拟现场仅提供ap-guangzhou地域");
                if (logTopic!=null&&!logTopic.isBlank()&&!logTopic.equals("application-logs")) return buildErrorResponse("模拟现场仅提供application-logs主题");
                if (safeQuery.matches("(?is).*(?:\\s(?:AND|OR|NOT)\\s|[<>]).*")) return buildErrorResponse("模拟日志不支持复合或数值条件，请用正文片段或单字段过滤");
                // Mock 模式：返回与告警关联的模拟日志数据
                logEntries = buildMockLogs(region, logTopic, safeQuery, actualLimit);
                logger.info("使用 Mock 数据，返回 {} 条日志", logEntries.size());
            } else {
                // 真实模式：调用 CLS API（这里预留接口，后续实现）
                return buildErrorResponse("CLS 真实查询尚未实现，请启用 mock 模式进行测试");
            }
            
            // 构建成功响应
            QueryLogsOutput output = new QueryLogsOutput();
            output.setSuccess(true);
            output.setRegion(region);
            output.setLogTopic(logTopic);
            output.setQuery(safeQuery.isBlank() ? "DEFAULT_QUERY" : safeQuery);
            output.setLogs(logEntries);
            output.setTotal(logEntries.size());
            output.setMessage(logEntries.isEmpty() ? "未找到匹配的日志" : String.format("成功查询到 %d 条日志", logEntries.size()));
            
            String jsonResult = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(output);
            logger.info("日志查询完成: 找到 {} 条日志", logEntries.size());
            
            return jsonResult;
            
        } catch (Exception e) {
            logger.error("查询日志失败", e);
            return buildErrorResponse("查询失败: " + e.getMessage());
        }
    }

    /**
     * 构建 Mock 日志数据
     * 返回 QueryMetricsTools 当前场景中定义的模拟观测日志。
     */
    private List<LogEntry> buildMockLogs(String region, String logTopic, String query, int limit) {
        List<QueryMetricsTools.AlertScenario> scenarios = queryMetricsTools.getScenarios();
        String currentScenarioId = queryMetricsTools.getCurrentScenarioId();

        if (scenarios == null || scenarios.isEmpty()) {
            throw new IllegalStateException("日志场景未加载，未生成替代日志");
        }

        QueryMetricsTools.AlertScenario scenario = scenarios.stream()
                .filter(candidate -> candidate.getScenarioId().equals(currentScenarioId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("当前场景不存在，未切换到其他日志"));

        List<QueryMetricsTools.LogEntry> scenarioLogs = scenario.getRelatedLogs();
        if (scenarioLogs == null || scenarioLogs.isEmpty()) {
            return new ArrayList<>();
        }

        return scenarioLogs.stream()
                .filter(log -> matchesMockLog(log,query))
                .limit(limit)
                .map(this::toLogEntry)
                .toList();
    }
    private boolean matchesMockLog(QueryMetricsTools.LogEntry log,String query) {
        if(query==null||query.isBlank()||query.strip().equals("*"))return true;
        var match=java.util.regex.Pattern.compile("(?i)^(level|service|instance):(.+)$").matcher(query.strip());
        if(match.matches()) {
            String actual=switch(match.group(1).toLowerCase(java.util.Locale.ROOT)){case "level"->log.getLevel();case "service"->log.getService();default->log.getInstance();};
            return match.group(2).strip().equalsIgnoreCase(actual);
        }
        return java.util.Objects.toString(log.getMessage(),"").toLowerCase(java.util.Locale.ROOT).contains(query.strip().toLowerCase(java.util.Locale.ROOT));
    }

    private LogEntry toLogEntry(QueryMetricsTools.LogEntry source) {
        LogEntry target = new LogEntry();
        target.setTimestamp(source.getTimestamp());
        target.setLevel(source.getLevel());
        target.setService(source.getService());
        target.setInstance(source.getInstance());
        target.setMessage(source.getMessage());
        target.setMetrics(new HashMap<>());
        return target;
    }

    /**
     * 构建错误响应
     */
    private String buildErrorResponse(String message) {
        try {
            QueryLogsOutput output = new QueryLogsOutput();
            output.setSuccess(false);
            output.setMessage(message);
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(output);
        } catch (Exception e) {
            return String.format("{\"success\":false,\"message\":\"%s\"}", message);
        }
    }
    
    // ==================== 数据模型 ====================
    
    /**
     * 日志条目
     */
    @Data
    public static class LogEntry {
        @JsonProperty("timestamp")
        private String timestamp;
        
        @JsonProperty("level")
        private String level;
        
        @JsonProperty("service")
        private String service;
        
        @JsonProperty("instance")
        private String instance;
        
        @JsonProperty("message")
        private String message;
        
        @JsonProperty("metrics")
        private Map<String, String> metrics;
    }
    
    /**
     * 日志查询输出
     */
    @Data
    public static class QueryLogsOutput {
        @JsonProperty("success")
        private boolean success;
        
        @JsonProperty("region")
        private String region;
        
        @JsonProperty("log_topic")
        private String logTopic;
        
        @JsonProperty("query")
        private String query;
        
        @JsonProperty("logs")
        private List<LogEntry> logs;
        
        @JsonProperty("total")
        private int total;
        
        @JsonProperty("message")
        private String message;
    }
    
    /**
     * 日志主题信息
     */
    @Data
    public static class LogTopicInfo {
        @JsonProperty("topic_name")
        private String topicName;
        
        @JsonProperty("description")
        private String description;
        
        @JsonProperty("example_queries")
        private List<String> exampleQueries;
        
        @JsonProperty("related_alerts")
        private List<String> relatedAlerts;
    }
    
    /**
     * 日志主题列表输出
     */
    @Data
    public static class LogTopicsOutput {
        @JsonProperty("success")
        private boolean success;
        
        @JsonProperty("topics")
        private List<LogTopicInfo> topics;
        
        @JsonProperty("available_regions")
        private List<String> availableRegions;
        
        @JsonProperty("default_region")
        private String defaultRegion;
        
        @JsonProperty("message")
        private String message;
    }
}
