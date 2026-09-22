package org.example.platform;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/** Read-only console projections; existing execution and usage endpoints stay unchanged. */
@RestController
@RequestMapping("/api/platform/admin/monitor")
public class MonitorController {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate db;
    public MonitorController(JdbcTemplate db) { this.db = db; }

    @GetMapping("/usage")
    public Object usage(@RequestParam(defaultValue="7") int days) {
        if (days != 7 && days != 30) throw PlatformCatalog.bad("统计范围只支持7天或30天");
        LocalDate last = LocalDate.now(ZONE), first = last.minusDays(days - 1L);
        Timestamp start = Timestamp.from(first.atStartOfDay(ZONE).toInstant());
        Timestamp end = Timestamp.from(last.plusDays(1).atStartOfDay(ZONE).toInstant());
        String metrics = "COUNT(*) AS calls, SUM(input_tokens) AS input_tokens, SUM(output_tokens) AS output_tokens, "
                + "SUM(total_tokens) AS total_tokens, SUM(elapsed_ms) AS elapsed_ms, "
                + "SUM(CASE WHEN total_tokens IS NULL THEN 1 ELSE 0 END) AS unknown_usage_calls, "
                + "SUM(CASE WHEN outcome='failed' THEN 1 ELSE 0 END) AS failed_calls ";
        var groups = db.queryForList("SELECT kind,model,purpose,outcome," + metrics
                + "FROM model_calls WHERE created_at>=? AND created_at<? GROUP BY kind,model,purpose,outcome ORDER BY model,kind,purpose,outcome", start,end);
        var daily = db.queryForList("SELECT to_char(created_at AT TIME ZONE 'Asia/Shanghai','YYYY-MM-DD') AS day,model," + metrics
                + "FROM model_calls WHERE created_at>=? AND created_at<? GROUP BY day,model ORDER BY day,model",start,end);
        return Map.of("days",days,"from",first.toString(),"through",last.toString(),"timezone","UTC+8","groups",groups,"daily",daily);
    }

    @GetMapping("/runs")
    public Object runs(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int size) {
        if (page < 1 || !Set.of(10,20,50).contains(size)) throw PlatformCatalog.bad("分页参数无效");
        long total = db.queryForObject("SELECT COUNT(*) FROM agent_runs",Long.class);
        long pages = Math.max(1,(total + size - 1) / size), actualPage = Math.min(page,pages);
        var items = db.queryForList("""
                SELECT r.id,r.agent_id AS "agentId",r.agent_version AS "agentVersion",r.status,
                       r.created_at AS "createdAt",r.finished_at AS "finishedAt",r.error_message AS error,
                       r.input_json::jsonb->>'question' AS question,r.result_json::jsonb->>'strategy' AS strategy,
                       s.origin
                FROM agent_runs r LEFT JOIN agent_sessions s ON s.id=r.session_id
                ORDER BY r.created_at DESC,r.id DESC LIMIT ? OFFSET ?
                """,size,(actualPage-1)*size);
        var statuses = db.queryForList("SELECT status,COUNT(*) AS count FROM agent_runs GROUP BY status ORDER BY status");
        return Map.of("items",items,"page",actualPage,"size",size,"total",total,"pages",pages,"statuses",statuses);
    }
}
