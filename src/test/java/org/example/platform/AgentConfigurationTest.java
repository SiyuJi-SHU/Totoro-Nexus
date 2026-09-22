package org.example.platform;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class AgentConfigurationTest {
    PlatformCatalog catalog;JdbcTemplate db;ObjectMapper json=new ObjectMapper();
    @BeforeEach void setup(){
        var data=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__platform_catalog.sql"),new ClassPathResource("db/migration/V5__session_origin.sql")).execute(data);
        db=new JdbcTemplate(data);catalog=new PlatformCatalog(db,json);catalog.initializeDefaults();
    }
    @Test void onlyThreeModesAreAcceptedWithoutCapabilitiesOrProfile(){
        for(String mode:List.of("react","workflow","plan_execute_replan")){
            var saved=catalog.saveAgent(null,new PlatformModels.AgentConfig("Agent","","任务","",List.of("existing-knowledge"),List.of("knowledge-tools"),mode,8,120,.1,10,3,2000));
            assertThat(saved.config().strategy()).isEqualTo(mode);assertThat(catalog.encode(saved.config())).doesNotContain("profile","capabilities");
        }
        assertThatThrownBy(()->catalog.saveAgent(null,new PlatformModels.AgentConfig("Agent","","任务","",List.of(),List.of(),"planned",8,120,.1,10,3,2000))).hasMessageContaining("执行模式");
    }
    @Test void migrationPublishesOneNewVersionAndPreservesOldSessionsAndConfiguration()throws Exception {
        var config=catalog.agent("oncall",null).config();var old=json.valueToTree(config).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode)old).remove("schemaVersion");
        ((com.fasterxml.jackson.databind.node.ObjectNode)old).put("profile","oncall").put("strategy","react").putArray("capabilities").add("incident_analysis");
        String historical=old.toString();db.update("UPDATE agent_versions SET config_json=? WHERE agent_id='oncall' AND version=1",historical);
        db.update("INSERT INTO agent_sessions(id,owner_id,agent_id,agent_version,title) VALUES('old-session','alice','oncall',1,'保留现场')");
        catalog.initializeDefaults();catalog.initializeDefaults();
        assertThat(catalog.agent("oncall",null).version()).isEqualTo(2);assertThat(catalog.agent("oncall",null).config().strategy()).isEqualTo("workflow");
        assertThat(db.queryForObject("SELECT config_json FROM agent_versions WHERE agent_id='oncall' AND version=1",String.class)).isEqualTo(historical);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM agent_sessions WHERE id='old-session'",Integer.class)).isEqualTo(1);
        assertThat(catalog.toolSets()).hasSize(1);
        assertThatThrownBy(()->catalog.activateVersion("oncall",1)).hasMessageContaining("升级前");
        assertThatThrownBy(()->new AgentRunStore(db,catalog).openVersionedSession("alice","oncall",1,"old","debug")).hasMessageContaining("升级前");
    }
}
