package org.example.platform;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.example.platform.PlatformModels.*;

/** Single-workspace catalog. All scope expansion is performed on the server. */
@Service
public class PlatformCatalog {
    public static final String LEGACY_DATASET = "existing-data";
    public static final String LEGACY_KNOWLEDGE = "existing-knowledge";
    private final JdbcTemplate db;
    private final ObjectMapper json;
    public PlatformCatalog(JdbcTemplate db, ObjectMapper json) { this.db = db; this.json = json; }
    public String encode(Object value) {
        try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalArgumentException("配置无法序列化", e); }
    }
    public <T> T decode(String value, Class<T> type) {
        try { return json.readValue(value, type); } catch (Exception e) { throw new IllegalStateException("保存的数据格式无效", e); }
    }
    private List<String> strings(String value) {
        try { return json.readValue(value, new TypeReference<List<String>>() {}); }
        catch (Exception e) { throw new IllegalStateException("保存的引用列表无效", e); }
    }
    public List<Dataset> datasets() {
        return db.query("SELECT id,name,description,parent_id FROM datasets ORDER BY created_at,id",
                (r,n) -> new Dataset(r.getString(1),r.getString(2),r.getString(3),r.getString(4)));
    }
    public Dataset dataset(String id) {
        return datasets().stream().filter(d -> d.id().equals(id)).findFirst().orElseThrow(() -> missing("数据集"));
    }
    @Transactional
    public Dataset saveDataset(String id, String name, String description, String parentId) {
        String key = id == null ? UUID.randomUUID().toString() : id;
        name = required(name, 160, "数据集名称"); description = optional(description, 2000);
        if (parentId != null && parentId.isBlank()) parentId = null;
        if (parentId != null) {
            dataset(parentId);
            if (key.equals(parentId) || expandDatasets(Set.of(key)).contains(parentId)) throw bad("数据集不能形成循环层级");
        }
        if (id == null) db.update("INSERT INTO datasets(id,name,description,parent_id) VALUES(?,?,?,?)", key,name,description,parentId);
        else { dataset(id); db.update("UPDATE datasets SET name=?,description=?,parent_id=? WHERE id=?",name,description,parentId,id); }
        return dataset(key);
    }
    public Set<String> expandDatasets(Set<String> selected) {
        Set<String> result = new LinkedHashSet<>(selected); List<Dataset> all = datasets(); boolean changed;
        do { changed = false; for (var d : all) if (d.parentId() != null && result.contains(d.parentId())) changed |= result.add(d.id()); } while (changed);
        return Collections.unmodifiableSet(result);
    }
    public List<KnowledgeBase> knowledgeBases() {
        return db.query("SELECT id,name,description,retrieval_mode FROM knowledge_bases ORDER BY created_at,id",
                (r,n) -> new KnowledgeBase(r.getString(1), r.getString(2), r.getString(3), r.getString(4),
                        db.queryForList("SELECT dataset_id FROM knowledge_base_datasets WHERE knowledge_base_id=? ORDER BY dataset_id",String.class,r.getString(1))));
    }
    public KnowledgeBase knowledgeBase(String id) {
        return knowledgeBases().stream().filter(k -> k.id().equals(id)).findFirst().orElseThrow(() -> missing("知识库"));
    }
    @Transactional
    public KnowledgeBase saveKnowledgeBase(String id, String name, String description, String mode, List<String> datasetIds) {
        String key = id == null ? UUID.randomUUID().toString() : id;
        name = required(name,160,"知识库名称"); description = optional(description,2000);
        if (!Set.of("semantic","keyword","hybrid").contains(mode)) throw bad("检索模式无效");
        List<String> ids = references(datasetIds, 100); ids.forEach(this::dataset);
        if (ids.isEmpty() && id != null) ids = knowledgeBase(id).datasetIds();
        if (ids.isEmpty()) ids = List.of(saveDataset(null,name+" 文档","由知识库自动管理",null).id());
        if (id == null) db.update("INSERT INTO knowledge_bases(id,name,description,retrieval_mode) VALUES(?,?,?,?)",key,name,description,mode);
        else { knowledgeBase(id); db.update("UPDATE knowledge_bases SET name=?,description=?,retrieval_mode=? WHERE id=?",name,description,mode,id); }
        db.update("DELETE FROM knowledge_base_datasets WHERE knowledge_base_id=?", key);
        for (String ds : ids) db.update("INSERT INTO knowledge_base_datasets(knowledge_base_id,dataset_id) VALUES(?,?)",key,ds);
        return knowledgeBase(key);
    }
    public record KnowledgeBaseDeletion(int datasets,int documents) {}
    @Transactional
    public KnowledgeBaseDeletion deleteKnowledgeBase(String id) {
        KnowledgeBase knowledgeBase=knowledgeBase(id);
        db.queryForList("SELECT id FROM knowledge_bases WHERE id=? FOR UPDATE",String.class,id);
        var usedBy=db.query("SELECT agent_id,version,config_json FROM agent_versions ORDER BY agent_id,version",
                (r,n)->Map.of("agentId",r.getString(1),"version",r.getInt(2),"config",decode(r.getString(3),AgentConfig.class))).stream()
                .filter(row->((AgentConfig)row.get("config")).knowledgeBaseIds().contains(id)).findFirst();
        if(usedBy.isPresent()) {
            var row=usedBy.get();var config=(AgentConfig)row.get("config");
            throw new ResponseStatusException(HttpStatus.CONFLICT,"知识库仍被智能体「"+config.name()+"」v"+row.get("version")+" 使用，请先修改或删除该智能体");
        }
        boolean activeEvaluation=db.queryForList("SELECT config_json FROM evaluation_jobs WHERE status IN ('queued','running')",String.class).stream()
                .map(config->decode(config,com.fasterxml.jackson.databind.JsonNode.class))
                .anyMatch(config->{var ids=config.path("knowledgeBaseIds");if(!ids.isArray())return false;for(var item:ids)if(id.equals(item.asText()))return true;return false;});
        if(activeEvaluation)throw new ResponseStatusException(HttpStatus.CONFLICT,"知识库正在用于评测，请先等待完成或停止评测");

        List<String> exclusiveDatasets=knowledgeBase.datasetIds().stream()
                .filter(datasetId->db.queryForObject("SELECT COUNT(*) FROM knowledge_base_datasets WHERE dataset_id=?",Integer.class,datasetId)==1)
                .toList();
        int removedDocuments=0;
        for(String datasetId:exclusiveDatasets)
            removedDocuments+=db.update("UPDATE documents SET status='DELETED',updated_at=CURRENT_TIMESTAMP WHERE dataset_id=? AND status<>'DELETED'",datasetId);
        db.update("DELETE FROM knowledge_base_datasets WHERE knowledge_base_id=?",id);
        db.update("DELETE FROM knowledge_bases WHERE id=?",id);
        return new KnowledgeBaseDeletion(exclusiveDatasets.size(),removedDocuments);
    }
    public Set<String> resolveDatasets(List<String> knowledgeBaseIds) {
        Set<String> selected = new LinkedHashSet<>();
        for (String id : references(knowledgeBaseIds, 30)) selected.addAll(knowledgeBase(id).datasetIds());
        return expandDatasets(selected);
    }
    public List<ToolSet> toolSets() {
        return db.query("SELECT id,name,description,tool_ids FROM tool_sets ORDER BY name,id",
                (r,n) -> new ToolSet(r.getString(1),r.getString(2),r.getString(3),strings(r.getString(4))));
    }
    public ToolSet toolSet(String id) { return toolSets().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow(() -> missing("工具集")); }
    public ToolSet saveToolSet(String id, String name, String description, List<String> toolIds) {
        if("knowledge-tools".equals(id))throw bad("内置只读工具由平台统一提供，无需编辑");
        String key = id == null ? UUID.randomUUID().toString() : id;
        name = required(name,160,"工具集名称"); description = optional(description,2000); String ids = encode(references(toolIds,50));
        if (id == null) db.update("INSERT INTO tool_sets(id,name,description,tool_ids) VALUES(?,?,?,?)",key,name,description,ids);
        else { toolSet(id); db.update("UPDATE tool_sets SET name=?,description=?,tool_ids=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",name,description,ids,id); }
        return toolSet(key);
    }
    public List<Agent> agents() {
        return db.query("SELECT a.id,a.current_version,a.enabled,v.config_json FROM agents a JOIN agent_versions v ON v.agent_id=a.id AND v.version=a.current_version ORDER BY a.created_at,a.id",
                (r,n) -> new Agent(r.getString(1),r.getInt(2),r.getBoolean(3),decode(r.getString(4),AgentConfig.class)));
    }
    public Agent agent(String id, Integer version) {
        String sql = version == null
                ? "SELECT a.id,a.current_version,a.enabled,v.config_json FROM agents a JOIN agent_versions v ON v.agent_id=a.id AND v.version=a.current_version WHERE a.id=?"
                : "SELECT a.id,v.version,a.enabled,v.config_json FROM agents a JOIN agent_versions v ON v.agent_id=a.id WHERE a.id=? AND v.version=?";
        Object[] args = version == null ? new Object[]{id} : new Object[]{id,version};
        var values = db.query(sql,(r,n) -> new Agent(r.getString(1),r.getInt(2),r.getBoolean(3),decode(r.getString(4),AgentConfig.class)),args);
        if (values.isEmpty()) throw missing("智能体版本"); return values.get(0);
    }
    @Transactional
    public Agent saveAgent(String id, AgentConfig raw) {
        AgentConfig config = validateConfig(raw); String key = id == null ? UUID.randomUUID().toString() : id; int version = 1;
        if (id == null) db.update("INSERT INTO agents(id,name,description,current_version) VALUES(?,?,?,1)",key,config.name(),config.description());
        else {
            var versions = db.queryForList("SELECT current_version FROM agents WHERE id=? FOR UPDATE",Integer.class,id);
            if (versions.isEmpty()) throw missing("智能体"); version = db.queryForObject("SELECT MAX(version) FROM agent_versions WHERE agent_id=?",Integer.class,id)+1;
            db.update("UPDATE agents SET name=?,description=?,current_version=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",config.name(),config.description(),version,id);
        }
        db.update("INSERT INTO agent_versions(agent_id,version,config_json) VALUES(?,?,?)",key,version,encode(config));
        return agent(key,version);
    }
    public List<Map<String,Object>> versions(String id) {
        var current=agent(id,null);
        return db.query("SELECT version,config_json FROM agent_versions WHERE agent_id=? ORDER BY version DESC",(r,n)->{
            Map<String,Object> out=new LinkedHashMap<>();out.put("version",r.getInt(1));out.put("config",decode(r.getString(2),AgentConfig.class));
            out.put("current",r.getInt(1)==current.version());return out;
        },id);
    }
    @Transactional
    public Agent activateVersion(String id,int version) {
        var selected=agent(id,version);
        if(selected.config().schemaVersion()!=2)throw bad("升级前配置仅供查看，请保存为新版本");
        db.queryForList("SELECT id FROM agents WHERE id=? FOR UPDATE",String.class,id);
        db.update("UPDATE agent_versions SET release_state='published' WHERE agent_id=? AND version=?",id,version);
        db.update("UPDATE agents SET current_version=?,name=?,description=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",version,selected.config().name(),selected.config().description(),id);
        return agent(id,null);
    }
    public void setAgentEnabled(String id, boolean enabled) {
        if (db.update("UPDATE agents SET enabled=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",enabled,id) == 0) throw missing("智能体");
    }
    @Transactional
    public void deleteAgent(String id) {
        agent(id,null);
        db.queryForList("SELECT id FROM agents WHERE id=? FOR UPDATE",String.class,id);
        if (db.queryForObject("SELECT COUNT(*) FROM agent_sessions WHERE agent_id=?",Integer.class,id)>0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"请先删除该智能体的全部对话记录");
        if (db.queryForObject("SELECT COUNT(*) FROM agent_runs WHERE agent_id=?",Integer.class,id)>0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"智能体仍有执行记录，暂时不能删除");
        db.update("DELETE FROM agent_versions WHERE agent_id=?",id);
        db.update("DELETE FROM agents WHERE id=?",id);
    }
    public AgentConfig validateConfig(AgentConfig c) {
        if (c == null) throw bad("缺少智能体配置");
        if(c.schemaVersion()!=2)throw bad("配置格式已升级，请刷新 Console 后重试");
        if (!Set.of("react","workflow","plan_execute_replan").contains(Objects.toString(c.strategy(),""))) throw bad("执行模式无效");
        if (c.maxToolCalls()<1 || c.maxToolCalls()>16 || c.timeoutSeconds()<10 || c.timeoutSeconds()>300) throw bad("工具预算须为1–16次，时限须为10–300秒");
        if (!Double.isFinite(c.temperature()) || c.temperature()<0 || c.temperature()>1) throw bad("温度须为0–1");
        if (c.topK()<1 || c.topK()>10 || c.candidateTopK()<c.topK() || c.candidateTopK()>100) throw bad("检索候选数或返回数无效");
        if (c.maxOutputTokens()<256 || c.maxOutputTokens()>8000) throw bad("最大输出Token须为256–8000");

        var kbs=references(c.knowledgeBaseIds(),30); var sets=references(c.toolSetIds(),20);
        if(c.strategy().equals("workflow")&&(kbs.isEmpty()||c.maxToolCalls()<4||c.timeoutSeconds()<30))
            throw bad("OnCall Workflow 需要知识库、至少4次工具调用和30秒时限");
        kbs.forEach(this::knowledgeBase); sets.forEach(this::toolSet);
        return new AgentConfig(required(c.name(),160,"智能体名称"),optional(c.description(),2000),required(c.instructions(),12000,"任务说明"),
                optional(c.greeting(),1000),kbs,sets,c.strategy(),c.maxToolCalls(),c.timeoutSeconds(),c.temperature(),c.candidateTopK(),c.topK(),c.maxOutputTokens(),2,
                !c.strategy().equals("workflow")&&c.allowGeneralKnowledge(),AgentModels.validate(c.chatModel()));
    }
    @Transactional
    public void initializeDefaults() {
        boolean firstInitialization = db.queryForObject("SELECT COUNT(*) FROM agents",Integer.class)==0
                && db.queryForObject("SELECT COUNT(*) FROM tool_sets WHERE id IN ('knowledge-tools','incident-tools')",Integer.class)==0;
        if (db.queryForObject("SELECT COUNT(*) FROM datasets WHERE id=?",Integer.class,LEGACY_DATASET)==0)
            db.update("INSERT INTO datasets(id,name,description) VALUES(?,?,?)",LEGACY_DATASET,"现有资料","从升级前知识库导入，保留原文档路径与评测别名");
        if (firstInitialization && db.queryForObject("SELECT COUNT(*) FROM knowledge_bases WHERE id=?",Integer.class,LEGACY_KNOWLEDGE)==0) {
            db.update("INSERT INTO knowledge_bases(id,name,description,retrieval_mode) VALUES(?,?,?,?)",LEGACY_KNOWLEDGE,"现有知识库","升级前资料的兼容检索范围","semantic");
            db.update("INSERT INTO knowledge_base_datasets(knowledge_base_id,dataset_id) VALUES(?,?)",LEGACY_KNOWLEDGE,LEGACY_DATASET);
        }
        var builtinIds=List.of("system.current_time","knowledge.list","knowledge.search","documents.find","documents.search","documents.sections","documents.read","materials.read");
        seedToolSet("knowledge-tools","内置只读工具",builtinIds);
        db.update("UPDATE tool_sets SET name=?,description=?,tool_ids=? WHERE id='knowledge-tools'","内置只读工具","所有智能体共享；资料与附件仍限于当前授权范围",encode(builtinIds));
        if (firstInitialization && db.queryForObject("SELECT COUNT(*) FROM agents WHERE id='oncall'",Integer.class)==0) {
            AgentConfig c = new AgentConfig("OnCall Agent","根据提交的告警和日志进行有证据的诊断","根据告警和日志调查问题，区分直接观察与待验证原因，给出有来源的检查建议。","请提交告警、日志和发生时间，我会分析现场并生成诊断报告。",List.of(LEGACY_KNOWLEDGE),List.of("knowledge-tools"),"workflow",10,180,0.1,10,3,4000);
            db.update("INSERT INTO agents(id,name,description,current_version) VALUES('oncall',?,?,1)",c.name(),c.description());
            db.update("INSERT INTO agent_versions(agent_id,version,config_json) VALUES('oncall',1,?)",encode(c));
        }
        // One-time migration: publish a new config; never rewrite archived session evidence or old versions.
        for(var agent:agents())if(agent.config().schemaVersion()!=2) {
            var c=agent.config();boolean oncall=agent.id().equals("oncall");
            var sets=new ArrayList<>(c.toolSetIds()==null?List.<String>of():c.toolSetIds());sets.remove("incident-tools");
            if(!sets.contains("knowledge-tools"))sets.add("knowledge-tools");
            saveAgent(agent.id(),new AgentConfig(c.name(),oncall?"根据提交的告警和日志进行有证据的诊断":c.description(),c.instructions(),
                oncall?"请提交告警、日志和发生时间，我会分析现场并生成诊断报告。":c.greeting(),c.knowledgeBaseIds(),sets,
                oncall?"workflow":c.strategy().equals("planned")?"plan_execute_replan":c.strategy(),
                oncall?Math.max(10,c.maxToolCalls()):c.maxToolCalls(),oncall?Math.max(180,c.timeoutSeconds()):c.timeoutSeconds(),c.temperature(),c.candidateTopK(),c.topK(),c.maxOutputTokens()));
        }
        db.update("DELETE FROM tool_sets WHERE id='incident-tools'");
    }
    private void seedToolSet(String id,String name,List<String> ids) {
        if (db.queryForObject("SELECT COUNT(*) FROM tool_sets WHERE id=?",Integer.class,id)==0)
            db.update("INSERT INTO tool_sets(id,name,description,tool_ids) VALUES(?,?,?,?)",id,name,"内置工具，可组合使用",encode(ids));
    }
    public static List<String> references(List<String> ids,int max) {
        if (ids==null) return List.of();
        if (ids.size()>max || ids.stream().anyMatch(s -> s==null || !s.matches("[A-Za-z0-9_.:-]{1,180}"))) throw bad("引用列表无效");
        return List.copyOf(new LinkedHashSet<>(ids));
    }
    public static String required(String s,int max,String label) { if (s==null || s.isBlank() || s.length()>max) throw bad(label+"为空或过长"); return s.strip(); }
    public static String optional(String s,int max) { if (s==null) return ""; if (s.length()>max) throw bad("文本超过长度限制"); return s.strip(); }
    public static ResponseStatusException bad(String text) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,text); }
    public static ResponseStatusException missing(String text) { return new ResponseStatusException(HttpStatus.NOT_FOUND,text+"不存在"); }
}
