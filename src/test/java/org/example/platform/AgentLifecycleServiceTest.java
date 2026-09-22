package org.example.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class AgentLifecycleServiceTest {
    @TempDir Path tempDir;
    JdbcTemplate db;
    PlatformCatalog catalog;
    AgentRunStore runs;
    SessionAttachmentService attachments;
    AgentLifecycleService lifecycle;

    @BeforeEach void setup() {
        var data=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V1__platform_catalog.sql"),
                new ClassPathResource("db/migration/V3__legacy_sessions.sql"),
                new ClassPathResource("db/migration/V4__session_attachments.sql"),
                new ClassPathResource("db/migration/V5__session_origin.sql")
        ).execute(data);
        db=new JdbcTemplate(data);
        db.execute("""
            CREATE TABLE model_calls (
                id VARCHAR(64) PRIMARY KEY, run_id VARCHAR(64), purpose VARCHAR(100) NOT NULL,
                kind VARCHAR(30) NOT NULL, model VARCHAR(160) NOT NULL, input_tokens INTEGER,
                output_tokens INTEGER, total_tokens INTEGER, elapsed_ms BIGINT NOT NULL,
                outcome VARCHAR(30) NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """);
        catalog=new PlatformCatalog(db,new ObjectMapper());
        catalog.initializeDefaults();
        db.update("INSERT INTO platform_users(id,username,password_hash,role) VALUES('alice','alice','unused','MEMBER')");
        runs=new AgentRunStore(db,catalog);
        attachments=new SessionAttachmentService(JdbcClient.create(data),runs,tempDir);
        lifecycle=new AgentLifecycleService(db,catalog,attachments);
    }

    @Test void historyMustBeRemovedBeforeAgentAndAttachmentFilesAreCleaned() throws Exception {
        var session=runs.openSession("alice","oncall",null,"删除测试");
        db.update("INSERT INTO agent_runs(id,session_id,agent_id,agent_version,status,input_json,context_json,finished_at) VALUES('run-1',?,?,1,'completed','{}','{}',CURRENT_TIMESTAMP)",session.id(),"oncall");
        db.update("INSERT INTO run_events(run_id,sequence,event_type,payload_json) VALUES('run-1',1,'terminal','{}')");
        db.update("INSERT INTO model_calls(id,run_id,purpose,kind,model,elapsed_ms,outcome) VALUES('call-1','run-1','answer','chat','test',1,'success')");
        var uploaded=attachments.upload(session.id(),new MockMultipartFile("file","log.txt","text/plain","error".getBytes(StandardCharsets.UTF_8)),"alice");
        Path stored=attachments.getFilePath(uploaded.id(),"alice");

        assertThatThrownBy(()->catalog.deleteAgent("oncall"))
                .isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(409));

        var deleted=lifecycle.deleteHistory("oncall");
        assertThat(deleted.sessions()).isEqualTo(1);
        assertThat(deleted.runs()).isEqualTo(1);
        assertThat(deleted.events()).isEqualTo(1);
        assertThat(deleted.attachments()).isEqualTo(1);
        assertThat(deleted.modelCalls()).isEqualTo(1);
        assertThat(Files.exists(stored)).isFalse();
        assertThat(catalog.agent("oncall",null).enabled()).isFalse();

        catalog.deleteAgent("oncall");
        assertThat(catalog.agents()).isEmpty();
        catalog.initializeDefaults();
        assertThat(catalog.agents()).isEmpty();
    }

    @Test void activeRunBlocksHistoryDeletion() {
        var session=runs.openSession("alice","oncall",null,"运行中");
        db.update("INSERT INTO agent_runs(id,session_id,agent_id,agent_version,status,input_json,context_json) VALUES('run-active',?,?,1,'running','{}','{}')",session.id(),"oncall");
        assertThatThrownBy(()->lifecycle.deleteHistory("oncall"))
                .isInstanceOfSatisfying(ResponseStatusException.class,e->{
                    assertThat(e.getStatusCode().value()).isEqualTo(409);
                    assertThat(e.getReason()).contains("正在执行");
                });
        assertThat(catalog.agent("oncall",null).enabled()).isTrue();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM agent_sessions WHERE agent_id='oncall'",Integer.class)).isEqualTo(1);
    }
}
