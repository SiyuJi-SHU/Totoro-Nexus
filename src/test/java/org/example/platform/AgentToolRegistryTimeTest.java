package org.example.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.service.ChatModelFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentToolRegistryTimeTest {
    private final ObjectMapper json=new ObjectMapper();
    private PlatformCatalog catalog;
    private AgentToolRegistry registry;
    private AgentToolRegistry.Context context;

    @BeforeEach void setup() {
        var data=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__platform_catalog.sql"),new ClassPathResource("db/migration/V5__session_origin.sql")).execute(data);
        catalog=new PlatformCatalog(new JdbcTemplate(data),json);catalog.initializeDefaults();
        var knowledge=mock(KnowledgeSearch.class);var mcp=mock(McpConnections.class);when(mcp.list()).thenReturn(List.of());
        registry=new AgentToolRegistry(catalog,knowledge,mcp,json,Clock.fixed(Instant.parse("2026-09-17T04:34:56Z"),ZoneOffset.UTC));
        var config=catalog.agent("oncall",null).config();
        context=new AgentToolRegistry.Context(new KnowledgeSearch.Scope(List.of(),Set.of(),List.of()),null,config,(type,dataValue)->{});
    }

    @Test void builtInToolsAreSharedWithoutRedundantBinding() {
        var tool=registry.all().stream().filter(t->t.id().equals("system.current_time")).findFirst().orElseThrow();
        assertThat(tool.name()).isEqualTo("current_datetime");
        assertThat(tool.schema()).extractingByKey("required").isEqualTo(List.of());
        assertThat(registry.allowed(catalog.agent("oncall",null).config())).extracting(AgentToolRegistry.Tool::id).contains("system.current_time");
        var unbound=new PlatformModels.AgentConfig("No tools","","Answer","",List.of(),List.of(),"react",4,60,.1,5,3,1000);
        assertThat(registry.allowed(unbound)).extracting(AgentToolRegistry.Tool::id).contains("system.current_time").doesNotContain("incident.read","plan.update");
    }

    @SuppressWarnings("unchecked")
    @Test void timeToolUsesHongKongByDefaultAndSupportsExplicitTimezone() throws Exception {
        var tool=registry.all().stream().filter(t->t.id().equals("system.current_time")).findFirst().orElseThrow();
        var defaultResult=(Map<String,Object>)registry.execute(tool,json.createObjectNode(),context);
        assertThat(defaultResult).containsEntry("timezone","Asia/Hong_Kong").containsEntry("isoDateTime","2026-09-17T12:34:56+08:00").containsEntry("utcOffset","+08:00").containsEntry("unixTimestamp",1789619696L);
        assertThat(context.evidence).hasSize(1);
        assertThat(context.evidence.values().iterator().next().content()).contains("当前日期时间：2026-09-17T12:34:56+08:00","时区：Asia/Hong_Kong");

        var utcResult=(Map<String,Object>)registry.execute(tool,json.createObjectNode().put("timezone","UTC"),context);
        assertThat(utcResult).containsEntry("timezone","UTC").containsEntry("isoDateTime","2026-09-17T04:34:56Z").containsEntry("utcOffset","+00:00");
        assertThat(context.evidence).hasSize(2);
    }

    @Test void invalidTimezoneIsRejectedWithAUsefulMessage() {
        var tool=registry.all().stream().filter(t->t.id().equals("system.current_time")).findFirst().orElseThrow();
        assertThatThrownBy(()->registry.execute(tool,json.createObjectNode().put("timezone","Mars/Olympus"),context)).hasMessageContaining("无效时区");
    }

    @Test void explicitCurrentDateAndTimePhrasesExposeTheClockTool() {
        assertThat(AgentExecution.wantsCurrentTime("香港现在的日期和时间是什么？")).isTrue();
        assertThat(AgentExecution.wantsCurrentTime("What is the current time in London?")).isTrue();
        assertThat(AgentExecution.wantsCurrentTime("说明任务执行时间管理的方法")).isFalse();
    }

    @Test void publicCatalogContainsOnlyBusinessTools() {
        assertThat(registry.all()).extracting(AgentToolRegistry.Tool::id).hasSize(8).doesNotContain("incident.read","plan.update","query.rewrite");
    }
}
