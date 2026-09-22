package org.example.platform;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** Performs the ordered, destructive cleanup required before an Agent can be deleted. */
@Service
public class AgentLifecycleService {
    public record HistoryDeletion(int sessions,int runs,int events,int attachments,int modelCalls) {}

    private final JdbcTemplate db;
    private final PlatformCatalog catalog;
    private final SessionAttachmentService attachments;

    public AgentLifecycleService(JdbcTemplate db,PlatformCatalog catalog,SessionAttachmentService attachments) {
        this.db=db;this.catalog=catalog;this.attachments=attachments;
    }

    @Transactional
    public HistoryDeletion deleteHistory(String agentId) {
        catalog.agent(agentId,null);
        db.queryForList("SELECT id FROM agents WHERE id=? FOR UPDATE",String.class,agentId);
        if (activeEvaluationExists(agentId))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"该智能体有正在运行的评测，请先等待完成或取消评测");
        if (db.queryForObject("SELECT COUNT(*) FROM agent_runs WHERE agent_id=? AND status IN ('queued','running','reviewing')",Integer.class,agentId)>0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"该智能体有正在执行的任务，请先等待完成或停止任务");

        // Stop new work while the user completes the second deletion step.
        db.update("UPDATE agents SET enabled=FALSE,updated_at=CURRENT_TIMESTAMP WHERE id=?",agentId);
        List<String> storagePaths=db.queryForList("""
            SELECT a.storage_path FROM session_attachments a
            JOIN agent_sessions s ON s.id=a.session_id
            WHERE s.agent_id=?
            """,String.class,agentId);
        attachments.deleteStoredFilesAfterCommit(storagePaths);

        int sessions=count("SELECT COUNT(*) FROM agent_sessions WHERE agent_id=?",agentId);
        int runs=count("SELECT COUNT(*) FROM agent_runs WHERE agent_id=?",agentId);
        int events=count("SELECT COUNT(*) FROM run_events WHERE run_id IN (SELECT id FROM agent_runs WHERE agent_id=?)",agentId);
        int attachmentCount=storagePaths.size();
        int modelCalls=count("SELECT COUNT(*) FROM model_calls WHERE run_id IN (SELECT id FROM agent_runs WHERE agent_id=?)",agentId);

        db.update("DELETE FROM legacy_session_aliases WHERE session_id IN (SELECT id FROM agent_sessions WHERE agent_id=?)",agentId);
        db.update("DELETE FROM run_events WHERE run_id IN (SELECT id FROM agent_runs WHERE agent_id=?)",agentId);
        db.update("DELETE FROM model_calls WHERE run_id IN (SELECT id FROM agent_runs WHERE agent_id=?)",agentId);
        db.update("DELETE FROM session_attachments WHERE session_id IN (SELECT id FROM agent_sessions WHERE agent_id=?)",agentId);
        db.update("DELETE FROM agent_runs WHERE agent_id=?",agentId);
        db.update("DELETE FROM agent_sessions WHERE agent_id=?",agentId);
        return new HistoryDeletion(sessions,runs,events,attachmentCount,modelCalls);
    }

    private int count(String sql,String agentId) {
        return db.queryForObject(sql,Integer.class,agentId);
    }

    private boolean activeEvaluationExists(String agentId) {
        return db.queryForList("SELECT config_json FROM evaluation_jobs WHERE status IN ('queued','running')",String.class).stream()
                .map(config->catalog.decode(config,JsonNode.class))
                .anyMatch(config->agentId.equals(config.path("agentId").asText()));
    }
}
