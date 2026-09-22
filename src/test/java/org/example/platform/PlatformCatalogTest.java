package org.example.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.example.platform.PlatformModels.*;

class PlatformCatalogTest {
    private PlatformCatalog catalog; private DocumentCatalog documents; private AgentRunStore runs;
    @BeforeEach void setup() {
        var data=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__platform_catalog.sql"), new ClassPathResource("db/migration/V5__session_origin.sql"),new ClassPathResource("db/migration/V6__agent_drafts.sql")).execute(data);
        var db=new JdbcTemplate(data);catalog=new PlatformCatalog(db,new ObjectMapper());documents=new DocumentCatalog(db,catalog);runs=new AgentRunStore(db,catalog);catalog.initializeDefaults();
    }
    @Test void agentVersionsAndDatasetExpansionRemainExplicit() {
        var ds=catalog.saveDataset(null,"研发资料","",null);
        var child=catalog.saveDataset(null,"API","",ds.id());
        var kb=catalog.saveKnowledgeBase(null,"研发知识","","semantic",List.of(ds.id()));
        assertThat(catalog.resolveDatasets(List.of(kb.id()))).containsExactlyInAnyOrder(ds.id(),child.id());
        assertThatThrownBy(()->catalog.saveDataset(ds.id(),ds.name(),"",child.id())).hasMessageContaining("循环");
        var initial=catalog.agent("oncall",null).config();
        var agent=catalog.saveAgent(null,new AgentConfig("新建 Agent","","只根据资料回答","",List.of(kb.id()),List.of("knowledge-tools"),"react",8,120,0.2,10,3,3000));
        catalog.saveAgent(agent.id(),initial);
        assertThat(catalog.agent(agent.id(),1).config().strategy()).isEqualTo("react");
        assertThat(catalog.agent(agent.id(),null).version()).isEqualTo(2);
        catalog.initializeDefaults();assertThat(catalog.agents()).hasSize(2);
    }
    @Test void builtInToolsetIsStableAndCannotBePartiallyOverwritten() {
        assertThatThrownBy(()->catalog.saveToolSet("knowledge-tools","知识检索与阅读","自定义",List.of("knowledge.search"))).hasMessageContaining("无需编辑");
        catalog.initializeDefaults();
        assertThat(catalog.toolSet("knowledge-tools").toolIds()).contains("knowledge.search","system.current_time","materials.read").doesNotContain("query.rewrite").hasSize(8);
        catalog.initializeDefaults();
        assertThat(catalog.toolSet("knowledge-tools").toolIds()).contains("knowledge.search","system.current_time","materials.read").doesNotContain("query.rewrite").hasSize(8);
    }
    @Test void modelSelectionIsValidatedVersionedAndOldJsonRemainsReadable() throws Exception {
        var json=new ObjectMapper();
        var original=catalog.agent("oncall",null).config();
        var legacy=json.valueToTree(original);((com.fasterxml.jackson.databind.node.ObjectNode)legacy).remove("chatModel");
        assertThat(json.treeToValue(legacy,AgentConfig.class).chatModel()).isNull();
        assertThat(AgentModels.resolve(original,"configured-default")).isEqualTo("configured-default");
        var edited=(com.fasterxml.jackson.databind.node.ObjectNode)legacy;
        edited.put("chatModel","deepseek-v4-pro");
        var pro=catalog.saveAgent("oncall",json.treeToValue(edited,AgentConfig.class));
        var session=runs.openSession("alice","oncall",null,"Model selection");
        edited.put("chatModel","deepseek-v4-flash");
        var flash=catalog.saveAgent("oncall",json.treeToValue(edited,AgentConfig.class));
        assertThat(catalog.agent("oncall",pro.version()).config().chatModel()).isEqualTo("deepseek-v4-pro");
        assertThat(catalog.agent("oncall",flash.version()).config().chatModel()).isEqualTo("deepseek-v4-flash");
        var continued=runs.openSession("alice","oncall",session.id(),"Follow-up");
        assertThat(catalog.agent("oncall",continued.agentVersion()).config().chatModel()).isEqualTo("deepseek-v4-pro");
        assertThat(runs.openSession("alice","oncall",null,"New session").agentVersion()).isEqualTo(flash.version());
        assertThat(catalog.agent("oncall",1).config().chatModel()).isNull();
        catalog.activateVersion("oncall",pro.version());
        assertThat(catalog.agent("oncall",null).config().chatModel()).isEqualTo("deepseek-v4-pro");
        edited.put("chatModel","qwen-unverified");
        assertThatThrownBy(()->catalog.saveAgent("oncall",json.treeToValue(edited,AgentConfig.class)))
                .hasMessageContaining("文本 LLM 不在可用列表");
    }
    @Test void knowledgeBaseCreatedWithoutInternalDatasetGetsItsOwnDocumentStore() {
        var kb=catalog.saveKnowledgeBase(null,"测试知识库","","semantic",List.of());
        assertThat(kb.datasetIds()).hasSize(1);
        assertThat(catalog.dataset(kb.datasetIds().get(0)).name()).isEqualTo("测试知识库 文档");
    }
    @Test void knowledgeBaseDeletionIsBlockedWhileReferencedThenRemovesExclusiveDocuments() {
        var kb=catalog.saveKnowledgeBase(null,"待删除知识库","","semantic",List.of());
        var document=documents.prepare(kb.datasetIds().get(0),"temporary.md","hash-delete",12,"vectors");documents.activate(document,1);
        var config=new AgentConfig("引用测试","","只根据资料回答","",List.of(kb.id()),List.of("knowledge-tools"),"react",4,60,.1,5,3,1000);
        var agent=catalog.saveAgent(null,config);
        assertThatThrownBy(()->catalog.deleteKnowledgeBase(kb.id()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("引用测试");
        catalog.deleteAgent(agent.id());
        var deleted=catalog.deleteKnowledgeBase(kb.id());
        assertThat(deleted.documents()).isEqualTo(1);
        assertThat(documents.list(kb.datasetIds().get(0))).isEmpty();
        assertThat(catalog.knowledgeBases()).noneMatch(item->item.id().equals(kb.id()));
    }
    @Test void deletingBuiltInKnowledgeBaseDoesNotRecreateIt() {
        catalog.deleteAgent("oncall");
        catalog.deleteKnowledgeBase(PlatformCatalog.LEGACY_KNOWLEDGE);
        catalog.initializeDefaults();
        assertThat(catalog.knowledgeBases()).noneMatch(item->item.id().equals(PlatformCatalog.LEGACY_KNOWLEDGE));
    }
    @Test void failedReplacementKeepsOldVersionSearchableAndOtherScopesCannotReadIt() {
        String ds=PlatformCatalog.LEGACY_DATASET;
        var old=documents.prepare(ds,"runbook.md","hash1",12,"vectors");documents.activate(old,1);
        var newer=documents.prepare(ds,"runbook.md","hash2",14,"vectors");documents.fail(newer,"injected vector failure");
        assertThat(documents.active(Set.of(ds))).singleElement().extracting(DocumentVersion::version).isEqualTo(old.version());
        assertThat(documents.list(ds)).singleElement().satisfies(d->{assertThat(d.status()).isEqualTo("INDEX_FAILED");assertThat(d.version()).isEqualTo(old.version());});
        assertThatThrownBy(()->documents.version(old.documentId(),old.version(),Set.of("elsewhere"))).hasMessageContaining("不存在");
        documents.remove(old.documentId());assertThat(documents.active(Set.of(ds))).isEmpty();
        assertThat(documents.version(old.documentId(),old.version(),Set.of(ds)).version()).isEqualTo(old.version());
    }
}
