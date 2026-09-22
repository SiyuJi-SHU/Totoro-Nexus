package org.example.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class SessionAttachmentServiceTest {
    @TempDir Path tempDir;
    JdbcTemplate db;
    SessionAttachmentService service;
    AgentRunStore runs;
    String session;
    @BeforeEach void setup() {
        var data = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__platform_catalog.sql"), new ClassPathResource("db/migration/V5__session_origin.sql"),
            new ClassPathResource("db/migration/V4__session_attachments.sql")).execute(data);
        db = new JdbcTemplate(data);
        var catalog = new PlatformCatalog(db, new ObjectMapper()); catalog.initializeDefaults();
        db.update("INSERT INTO platform_users(id,username,password_hash,role) VALUES('alice','alice','unused','MEMBER')");
        runs = new AgentRunStore(db,catalog);
        session = runs.openSession("alice","oncall",null,"Attachment test").id();
        service = new SessionAttachmentService(JdbcClient.create(data),runs,tempDir);
    }
    MockMultipartFile file(String name,String text) {
        return new MockMultipartFile("file",name,"text/plain",text.getBytes(StandardCharsets.UTF_8));
    }
    void notFound(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ResponseStatusException.class,
            e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
    }
    @Test void uploadAndList() throws Exception {
        var a=service.upload(session,file("incident.log","service=api 错误 ECONNRESET"),"alice");
        assertThat(service.readContent(a.id(),"alice")).isEqualTo("service=api 错误 ECONNRESET");
        assertThat(service.list(session,"alice")).singleElement().satisfies(x -> {
            assertThat(x.filename()).isEqualTo("incident.log"); assertThat(x.storagePath()).isNull();
        });
        assertThat(service.getFilePath(a.id(),"alice")).startsWith(tempDir);
        assertThat(db.queryForObject("SELECT context_revision FROM agent_sessions WHERE id=?",Integer.class,session)).isEqualTo(2);
    }
    @Test void downloadRequiresOwner() throws Exception {
        var a=service.upload(session,file("secret.txt","secret"),"alice");
        notFound(() -> service.readContent(a.id(),"bob"));
        notFound(() -> service.getFilePath(a.id(),"bob"));
        notFound(() -> service.list(session,"bob"));
        assertThat(service.readContent(a.id(),"alice")).isEqualTo("secret");
    }
    @Test void deleteRequiresOwnerAndMatchingSession() throws Exception {
        var a=service.upload(session,file("log.txt","original"),"alice");
        var path=service.getFilePath(a.id(),"alice");
        String other=runs.openSession("alice","oncall",null,"other").id();
        notFound(() -> service.delete(session,a.id(),"bob"));
        notFound(() -> service.delete(other,a.id(),"alice"));
        assertThat(Files.exists(path)).isTrue();
        service.delete(session,a.id(),"alice");
        assertThat(service.list(session,"alice")).isEmpty();
        assertThat(Files.exists(path)).isFalse();
        assertThat(db.queryForObject("SELECT context_revision FROM agent_sessions WHERE id=?",Integer.class,session)).isEqualTo(3);
    }
    @Test void unsafeNameAndInvalidUtf8LeaveNoMetadataOrFiles() throws Exception {
        assertThatThrownBy(() -> service.upload(session,file("../bad.txt","text"),"alice")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.upload(session,new MockMultipartFile("file","bad.txt","text/plain",new byte[]{(byte)0xff}),"alice")).isInstanceOf(ResponseStatusException.class);
        assertThat(service.list(session,"alice")).isEmpty();
        try(var files=Files.walk(tempDir)) { assertThat(files.filter(Files::isRegularFile).count()).isZero(); }
    }
}
