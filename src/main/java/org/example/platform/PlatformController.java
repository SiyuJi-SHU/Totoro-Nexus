package org.example.platform;

import org.example.service.ChatModelFactory;
import org.example.agent.tool.QueryMetricsTools;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.multipart.MultipartFile;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

@RestController
@RequestMapping("/api/platform")
public class PlatformController {
    private final PlatformCatalog catalog;private final DocumentCatalog documents;private final KnowledgeIngestion ingestion;
    private final KnowledgeSearch search;private final SourceStorage sources;private final AgentToolRegistry tools;
    private final McpConnections mcp;private final PlatformIdentity identity;private final AgentRuntime runtime;
    private final AgentRunStore runs;private final UsageLedger usage;private final ChatModelFactory models;private final QueryMetricsTools scenarios;
    private final AgentLifecycleService agentLifecycle;
    public PlatformController(PlatformCatalog catalog,DocumentCatalog documents,KnowledgeIngestion ingestion,KnowledgeSearch search,SourceStorage sources,
                              AgentToolRegistry tools,McpConnections mcp,PlatformIdentity identity,AgentRuntime runtime,AgentRunStore runs,
                              UsageLedger usage,ChatModelFactory models,QueryMetricsTools scenarios,AgentLifecycleService agentLifecycle) {
        this.catalog=catalog;this.documents=documents;this.ingestion=ingestion;this.search=search;this.sources=sources;this.tools=tools;
        this.mcp=mcp;this.identity=identity;this.runtime=runtime;this.runs=runs;this.usage=usage;this.models=models;this.scenarios=scenarios;this.agentLifecycle=agentLifecycle;
    }
    @GetMapping("/agents") public Object agents(Authentication user){identity.userId(user);return catalog.agents();}
    @GetMapping("/agents/{id}") public Object agent(Authentication user,@PathVariable String id,@RequestParam(required=false) Integer version){identity.userId(user);return catalog.agent(id,version);}
    @GetMapping("/scenarios") public Object scenarios(Authentication user){if(!identity.admin(user))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"仅管理员调试使用模拟场景");return scenarios.getScenarios();}
    @GetMapping("/models") public Object models(){return Map.of("chat",models.modelName(),"chatModels",AgentModels.OPTIONS,"embedding","qwen3.7-text-embedding-flash","rerank","gte-rerank-v2");}
    @GetMapping("/sessions") public Object sessions(Authentication user,@RequestParam String agentId,@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="50") int limit){return runs.sessions(identity.userId(user),agentId,offset,limit);}
    public record OpenSession(String agentId,String origin,Integer agentVersion){}
    @PostMapping("/sessions") public Object openSession(Authentication user,@RequestBody OpenSession input){String origin=input.origin()==null?"user":input.origin();if((!origin.equals("user")||input.agentVersion()!=null)&&!identity.admin(user))throw PlatformCatalog.bad("仅管理员可创建调试会话");return runs.openVersionedSession(identity.userId(user),input.agentId(),origin.equals("user")?null:input.agentVersion(),"新会话",origin);}
    @GetMapping("/sessions/{id}") public Object session(Authentication user,@PathVariable String id){return runs.history(id,identity.userId(user)).stream().map(this::viewRun).toList();}
    @PostMapping("/runs") public Object start(Authentication user,@RequestBody AgentRuntime.Input input){if(input.scenarioId()!=null&&!input.scenarioId().isBlank()&&!identity.admin(user))throw PlatformCatalog.bad("模拟场景仅用于管理员调试");String owner=identity.userId(user);
        if(input.scenarioId()!=null&&!input.scenarioId().isBlank()) {
            if(input.sessionId()==null||input.sessionId().isBlank()) {var session=runs.openVersionedSession(owner,input.agentId(),null,input.question(),"debug");input=new AgentRuntime.Input(input.agentId(),session.id(),input.question(),input.scenarioId(),input.diagnose(),input.incidentText());}
            else {runs.session(input.sessionId(),owner);if(runs.origin(input.sessionId()).equals("user"))throw PlatformCatalog.bad("模拟场景请在管理员调试会话运行");}
        }
        return viewRun(runtime.start(owner,input));}
    @GetMapping("/runs/{id}") public Object run(Authentication user,@PathVariable String id){return viewRun(runs.owned(id,identity.userId(user)));}
    @PostMapping("/runs/{id}/cancel") public Object cancel(Authentication user,@PathVariable String id){return viewRun(runtime.cancel(id,identity.userId(user)));}
    @GetMapping("/runs/{id}/events") public Object events(Authentication user,@PathVariable String id,@RequestParam(defaultValue="0") int after){runs.owned(id,identity.userId(user));return runs.events(id,after);}
    @GetMapping("/runs/{id}/usage") public Object runUsage(Authentication user,@PathVariable String id){runs.owned(id,identity.userId(user));return usage.forRun(id);}
    private Object viewRun(AgentRunStore.Run r) {
        Map<String,Object> out=new LinkedHashMap<>();out.put("id",r.id());out.put("origin",runs.origin(r.sessionId()));out.put("sessionId",r.sessionId());out.put("agentId",r.agentId());out.put("agentVersion",r.agentVersion());out.put("status",r.status());
        out.put("input",catalog.decode(r.inputJson(),Object.class));out.put("incident",catalog.decode(r.contextJson(),AgentRuntime.Context.class).incident());out.put("result",r.resultJson()==null?null:catalog.decode(r.resultJson(),Object.class));out.put("error",r.error());out.put("createdAt",r.createdAt());out.put("finishedAt",r.finishedAt());return out;
    }
    @GetMapping("/admin/catalog") public Object catalog(){return Map.of("agents",catalog.agents(),"datasets",catalog.datasets(),"knowledgeBases",catalog.knowledgeBases(),"toolSets",catalog.toolSets(),"tools",tools.all(),"mcp",mcp.list());}
    @PostMapping("/admin/agents") public Object createAgent(@RequestBody PlatformModels.AgentConfig config){return catalog.saveAgent(null,config);}
    @PutMapping("/admin/agents/{id}") public Object updateAgent(@PathVariable String id,@RequestBody PlatformModels.AgentConfig config){return catalog.saveAgent(id,config);}
    @PutMapping("/admin/agents/{id}/enabled") public Object enabled(@PathVariable String id,@RequestBody Map<String,Boolean> body){catalog.setAgentEnabled(id,Boolean.TRUE.equals(body.get("enabled")));return catalog.agent(id,null);}
    @DeleteMapping("/admin/agents/{id}/sessions") public Object deleteAgentHistory(@PathVariable String id){return agentLifecycle.deleteHistory(id);}
    @DeleteMapping("/admin/agents/{id}") public Object deleteAgent(@PathVariable String id){catalog.deleteAgent(id);return Map.of("deleted",true);}
    @GetMapping("/admin/agents/{id}/versions") public Object agentVersions(@PathVariable String id){return catalog.versions(id);}
    public record ActivateVersion(int version){}
    @PostMapping("/admin/agents/{id}/activate") public Object activate(@PathVariable String id,@RequestBody ActivateVersion input){return catalog.activateVersion(id,input.version());}
    @PostMapping("/admin/datasets") public Object dataset(@RequestBody PlatformModels.Dataset input){return catalog.saveDataset(null,input.name(),input.description(),input.parentId());}
    @PutMapping("/admin/datasets/{id}") public Object dataset(@PathVariable String id,@RequestBody PlatformModels.Dataset input){return catalog.saveDataset(id,input.name(),input.description(),input.parentId());}
    @PostMapping("/admin/knowledge-bases") public Object kb(@RequestBody PlatformModels.KnowledgeBase input){return catalog.saveKnowledgeBase(null,input.name(),input.description(),input.retrievalMode(),input.datasetIds());}
    @PutMapping("/admin/knowledge-bases/{id}") public Object kb(@PathVariable String id,@RequestBody PlatformModels.KnowledgeBase input){return catalog.saveKnowledgeBase(id,input.name(),input.description(),input.retrievalMode(),input.datasetIds());}
    @DeleteMapping("/admin/knowledge-bases/{id}") public Object deleteKnowledgeBase(@PathVariable String id){return catalog.deleteKnowledgeBase(id);}
    @PostMapping("/admin/tool-sets") public Object toolSet(@RequestBody PlatformModels.ToolSet input){tools.validateToolIds(input.toolIds());return catalog.saveToolSet(null,input.name(),input.description(),input.toolIds());}
    @PutMapping("/admin/tool-sets/{id}") public Object toolSet(@PathVariable String id,@RequestBody PlatformModels.ToolSet input){tools.validateToolIds(input.toolIds());return catalog.saveToolSet(id,input.name(),input.description(),input.toolIds());}
    @GetMapping("/admin/documents") public Object documents(@RequestParam(required=false) String datasetId, @RequestParam(required=false) String knowledgeBaseId){
        if(knowledgeBaseId!=null){
            var kb=catalog.knowledgeBase(knowledgeBaseId);
            var datasetIds=catalog.expandDatasets(new LinkedHashSet<>(kb.datasetIds()));
            // list方法接受String或null，需要遍历多个数据集
            return datasetIds.stream()
                .flatMap(id->documents.list(id).stream())
                .distinct()
                .toList();
        }
        return documents.list(datasetId);
    }
    @GetMapping("/admin/documents/{id}/versions") public Object documentVersions(@PathVariable String id){return documents.versions(id);}
    @GetMapping("/admin/documents/{id}/preview") public Object preview(@PathVariable String id) throws Exception {
        var versions=documents.versions(id);
        if(versions.isEmpty())throw PlatformCatalog.missing("文档");
        var latest=versions.get(0);
        var source=sources.read(latest);
        var content=new org.example.service.DocumentReadingService().read(source,latest.version(),null,0,20_000);
        return Map.of("documentId",id,"sourceFile",content.sourceFile(),"version",latest.version(),"content",content.content(),"truncated",content.truncated());
    }
    @GetMapping("/admin/documents/{id}/source") public Object source(@PathVariable String id,@RequestParam String version,@RequestParam(defaultValue="0") int offset) throws Exception {
        var v=documents.versions(id).stream().filter(x->x.version().equals(version)).findFirst().orElseThrow(()->PlatformCatalog.missing("文档版本"));
        var source=sources.read(v);return new org.example.service.DocumentReadingService().read(source,version,null,offset,20000);
    }
    @PostMapping("/admin/documents/upload") public Object upload(@RequestParam(required=false) String datasetId,@RequestParam(required=false) String knowledgeBaseId,@RequestParam(defaultValue="") String folder,@RequestParam MultipartFile file)throws Exception{
        String targetDatasetId = datasetId;
        if(knowledgeBaseId!=null){
            var kb=catalog.knowledgeBase(knowledgeBaseId);
            if(kb.datasetIds().isEmpty())throw PlatformCatalog.bad("知识库未绑定数据集");
            targetDatasetId=kb.datasetIds().get(0); // 上传到第一个数据集
        }
        if(targetDatasetId==null)throw PlatformCatalog.bad("必须指定datasetId或knowledgeBaseId");
        return ingestion.upload(targetDatasetId,folder,file);
    }
    @PostMapping("/admin/documents/{id}/reindex") public Object reindex(@PathVariable String id)throws Exception{return ingestion.reindex(id);}
    @DeleteMapping("/admin/documents/{id}") public Object remove(@PathVariable String id){documents.remove(id);return Map.of("deleted",true);}
    @PostMapping("/admin/migrate-legacy") public Object migrate()throws Exception{return ingestion.migrateLegacy();}
    public record Recall(List<String> knowledgeBaseIds,List<String> datasetIds,String query,String mode,Integer candidateTopK,Integer topK,Boolean rerank){}
    @PostMapping("/admin/recall") public Object recall(@RequestBody Recall input)throws Exception {
        KnowledgeSearch.Scope scope;
        if(input.datasetIds()!=null&&!input.datasetIds().isEmpty()) {var selected=new LinkedHashSet<>(PlatformCatalog.references(input.datasetIds(),100));selected.forEach(catalog::dataset);var expanded=catalog.expandDatasets(selected);scope=new KnowledgeSearch.Scope(List.of(),expanded,documents.active(expanded));}
        else scope=search.scope(input.knowledgeBaseIds());
        return search.search(scope,input.query(),input.mode()==null?"semantic":input.mode(),input.candidateTopK()==null?10:input.candidateTopK(),input.topK()==null?3:input.topK(),!Boolean.FALSE.equals(input.rerank()));
    }
    public record ConnectionInput(String name,String endpoint,String credentialEnv,boolean enabled){}
    @PostMapping("/admin/mcp") public Object mcp(@RequestBody ConnectionInput input){return mcp.save(null,input.name(),input.endpoint(),input.credentialEnv(),input.enabled());}
    @PutMapping("/admin/mcp/{id}") public Object mcp(@PathVariable String id,@RequestBody ConnectionInput input){return mcp.save(id,input.name(),input.endpoint(),input.credentialEnv(),input.enabled());}
    @PostMapping("/admin/mcp/{id}/discover") public Object discover(@PathVariable String id){return mcp.discover(id);}
    public record Debug(String agentId,String toolId,JsonNode arguments){}
    @PostMapping("/admin/tools/debug") public Object debug(@RequestBody Debug input)throws Exception {
        var agent=catalog.agent(input.agentId(),null);var tool=tools.allowed(agent.config()).stream().filter(t->t.id().equals(input.toolId())).findFirst().orElseThrow(()->PlatformCatalog.bad("工具未绑定到该智能体"));
        List<Object> events=new ArrayList<>();var context=new AgentToolRegistry.Context(search.scope(agent.config().knowledgeBaseIds()),null,agent.config(),(type,data)->events.add(Map.of("type",type,"data",data)));
        try(var tracked=UsageLedger.bind(null,u->events.add(Map.of("type","usage","data",u)))) {return Map.of("result",tools.execute(tool,input.arguments(),context),"events",events);}
    }
    @GetMapping("/admin/runs") public Object runs(){return runs.recent().stream().map(this::viewRun).toList();}
    @GetMapping("/admin/runs/{id}/events") public Object adminEvents(@PathVariable String id,@RequestParam(defaultValue="0") int after){runs.get(id);return runs.events(id,after);}
    @GetMapping("/admin/usage") public Object usage(){return Map.of("scope","平台启动统一计量后所有聊天、改写、审校、评测、Embedding与Rerank调用；未知Token保留空值","groups",usage.summary());}
}
