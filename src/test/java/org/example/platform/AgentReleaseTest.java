package org.example.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentReleaseTest {
    @Test void saveActivatesImmediatelyAndRollbackPreservesSessionsAndVersionSequence() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__platform_catalog.sql"),new ClassPathResource("db/migration/V5__session_origin.sql"),new ClassPathResource("db/migration/V6__agent_drafts.sql")).execute(ds);
        var db=new JdbcTemplate(ds);var catalog=new PlatformCatalog(db,new ObjectMapper());catalog.initializeDefaults();
        var store=new AgentRunStore(db,catalog);var old=store.openSession("alice","oncall",null,"old session");
        var config=catalog.agent("oncall",null).config();var saved=catalog.saveAgent("oncall",config);
        assertEquals(2,saved.version());assertEquals(2,catalog.agent("oncall",null).version());
        assertEquals(1,store.session(old.id(),"alice").agentVersion());
        assertEquals(1,catalog.activateVersion("oncall",1).version());
        assertEquals(3,catalog.saveAgent("oncall",config).version());
    }
}
