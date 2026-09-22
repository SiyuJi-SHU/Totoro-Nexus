package org.example.platform;

import org.example.dto.IncidentSnapshot;
import org.example.service.*;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.BiConsumer;

/** Allowed tools are resolved once per run; models cannot expand permissions by naming another tool. */
@Service
public class AgentToolRegistry {
    public record Tool(String id,String name,String title,String description,Map<String,Object> schema,String source,boolean readOnly) {}
    public record Evidence(String id,String documentId,String version,String sourceFile,String title,int start,int end,String content,String kind) {}
    public static final class Context {
        public final KnowledgeSearch.Scope scope;
        public final IncidentSnapshot incident;
        public final PlatformModels.AgentConfig config;
        public final Map<String,Evidence> evidence=new LinkedHashMap<>();
        public final Map<String,Object> cache=new HashMap<>();
        public final BiConsumer<String,Object> emit;
        public List<Map<String,Object>> plan=List.of();
        public boolean diagnostic;
        AnswerPolicy answerPolicy=AnswerPolicy.GROUNDED;
        public boolean generalAllowed;
        public boolean openQuestion;
        public boolean lookupFailed;
        public int searches;
        public int evidenceChars;
        public final Set<String> locatedDocumentIds=new LinkedHashSet<>();
        public List<AgentRuntime.Context.AttachmentContent> materials=List.of();
        public Context(KnowledgeSearch.Scope scope,IncidentSnapshot incident,PlatformModels.AgentConfig config,BiConsumer<String,Object> emit){this.scope=scope;this.incident=incident;this.config=config;this.emit=emit;}
    }
    private static final String DEFAULT_TIME_ZONE="Asia/Hong_Kong";
    private final PlatformCatalog catalog;private final KnowledgeSearch knowledge;private final McpConnections mcp;private final ObjectMapper json;private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired
    public AgentToolRegistry(PlatformCatalog catalog,KnowledgeSearch knowledge,McpConnections mcp,ObjectMapper json){this(catalog,knowledge,mcp,json,Clock.systemUTC());}
    AgentToolRegistry(PlatformCatalog catalog,KnowledgeSearch knowledge,McpConnections mcp,ObjectMapper json,Clock clock){this.catalog=catalog;this.knowledge=knowledge;this.mcp=mcp;this.json=json;this.clock=clock;}
    public List<Tool> all() {
        List<Tool> result=new ArrayList<>(builtins());
        for(var c:mcp.list())if(c.enabled())for(var t:c.tools())result.add(new Tool(t.id(),t.name(),t.remoteName(),t.description(),t.inputSchema(),"MCP: "+c.name(),t.readOnly()));
        return List.copyOf(result);
    }
    public List<Tool> allowed(PlatformModels.AgentConfig config) {
        Set<String> ids=new LinkedHashSet<>();builtins().forEach(t->ids.add(t.id()));
        config.toolSetIds().forEach(id->ids.addAll(catalog.toolSet(id).toolIds()));
        return all().stream().filter(t->ids.contains(t.id())&&t.readOnly()).toList();
    }
    public void validateToolIds(List<String> ids){Set<String> known=new HashSet<>();all().forEach(t->known.add(t.id()));if(ids!=null&&ids.stream().anyMatch(id->!known.contains(id)))throw PlatformCatalog.bad("工具集含未发现的工具");}
    public List<ToolCallback> callbacks(List<Tool> tools) {
        return tools.stream().map(t->(ToolCallback)new ToolCallback(){
            public ToolDefinition getToolDefinition(){return ToolDefinition.builder().name(t.name()).description(t.description()).inputSchema(catalog.encode(modelSchema(t))).build();}
            public String call(String input){throw new IllegalStateException("工具必须由有预算与记录的运行控制器执行");}
        }).toList();
    }
    /** Explicit positions in model calls prevent an omitted offset from meaning "read the same page again".
     * Direct/legacy callers retain the existing default-window behavior. */
    static Map<String,Object> modelSchema(Tool tool) {
        if(!tool.id().equals("documents.read"))return tool.schema();
        Map<String,Object> schema=new LinkedHashMap<>(tool.schema());schema.put("required",List.of("documentId","version","offset"));return schema;
    }
    public Object execute(Tool tool,JsonNode input,Context c) throws Exception {
        validateInput(tool,input);
        String searchMode=tool.id().equals("knowledge.search")?searchMode(input,c):null;
        String key=tool.id()+":"+catalog.encode(json.convertValue(input,TreeMap.class))+(searchMode==null?"":":mode="+searchMode);
        boolean cacheable=!tool.id().equals("system.current_time");
        if(cacheable&&c.cache.containsKey(key)){c.emit.accept("cache_hit",Map.of("tool",tool.id()));return c.cache.get(key);}
        if(isSearch(tool)&&c.searches>=searchLimit(c))return Map.of("status","search_limit","message","本次已完成足够的检索尝试。停止换词搜索；根据已有证据回答或如实说明未找到，必须查证的事实不能编造。");
        if(isSearch(tool))c.searches++;
        Object result;
        switch(tool.id()) {
            case "system.current_time" -> {
                var value=currentTime(input,clock);
                String content="当前日期时间："+value.get("isoDateTime")+"\n时区："+value.get("timezone")+"\nUTC偏移："+value.get("utcOffset")+"\nUnix时间戳："+value.get("unixTimestamp");
                var evidence=new Evidence("T-"+KnowledgeFiles.digest(value.get("timezone")+":"+value.get("unixTimestamp")).substring(0,20),"",Objects.toString(value.get("unixTimestamp")),"系统时钟","当前日期时间",0,content.length(),content,"tool");
                addEvidence(c,evidence);
                value.put("evidence",evidence);
                result=value;
            }
            case "knowledge.list" -> {
                var listed=c.scope.documents().stream().limit(100).toList();listed.forEach(v->c.locatedDocumentIds.add(v.documentId()));
                var bases=catalog.knowledgeBases().stream().filter(k->c.scope.knowledgeBaseIds().contains(k.id()))
                        .map(k->Map.of("id",k.id(),"name",k.name(),"description",k.description())).toList();
                var datasets=catalog.datasets().stream().filter(d->c.scope.datasetIds().contains(d.id()))
                        .map(d->Map.of("id",d.id(),"name",d.name())).toList();
                result=Map.of("knowledgeBaseIds",c.scope.knowledgeBaseIds(),"knowledgeBases",bases,"datasets",datasets,
                        "documents",listed.stream().map(v->Map.of("documentId",v.documentId(),"datasetId",v.datasetId(),"version",v.version(),"sourceFile",v.path(),"length",v.contentLength())).toList(),
                        "total",c.scope.documents().size(),"returned",listed.size(),"truncated",listed.size()<c.scope.documents().size());
            }
            case "knowledge.search" -> {
                var found=knowledge.search(c.scope,text(input,"query"),searchMode,c.config.candidateTopK(),c.config.topK(),true);
                c.lookupFailed|=found.degraded()||"error".equals(found.status());
                c.emit.accept("retrieval",found);
                var relevant=relevantDocuments(found.documents());
                result=Map.of("status",found.degraded()?"degraded":relevant.isEmpty()?"no_results":found.status(),"query",found.query(),"mode",found.mode(),"notices",found.notices(),"documents",remember(relevant,c));
            }
            case "documents.find" -> {
                var matches=knowledge.findDocuments(c.scope,text(input,"query"));matches.forEach(match->c.locatedDocumentIds.add(Objects.toString(match.get("documentId"),"")));
                result=Map.of("matches",matches);
            }
            case "documents.search" -> result=Map.of("documents",remember(knowledge.searchText(c.scope,text(input,"query"),input.path("documentId").asText(null),c.config.topK()),c));
            case "documents.sections" -> result=Map.of("sections",knowledge.sections(c.scope,text(input,"documentId"),text(input,"version")));
            case "documents.read" -> {
                int remaining=60000-c.evidenceChars;
                if(remaining<1)return Map.of("status","budget_exhausted","message","本次证据上下文已达到上限，请根据已有证据回答");
                int length=Math.min(remaining,input.path("maxChars").asInt(6000));
                Integer offset=input.hasNonNull("offset")?input.get("offset").intValue():null;
                if(offset==null&&!input.hasNonNull("sectionId"))offset=c.evidence.values().stream()
                        .filter(e->e.documentId().equals(text(input,"documentId"))&&e.version().equals(text(input,"version")))
                        .map(e->Math.max(0,e.start()-1000)).findFirst().orElse(0);
                var window=knowledge.read(c.scope,text(input,"documentId"),text(input,"version"),input.path("sectionId").asText(null),offset,length);
                String id="D-"+KnowledgeFiles.digest(window.documentId()+":"+window.version()+":"+window.start()+":"+window.end()).substring(0,20);
                var evidence=new Evidence(id,window.documentId(),window.version(),window.sourceFile(),"原文读取",window.start(),window.end(),window.content(),"document");
                var combined=new ArrayList<>(c.evidence.values());combined.add(evidence);
                boolean covered=EvidenceContext.size(combined)==c.evidenceChars;
                int before=c.evidenceChars;if(!covered)addEvidence(c,evidence);
                Map<String,Object> read=new LinkedHashMap<>();
                if(covered)read.put("existingEvidenceIds",c.evidence.values().stream()
                    .filter(e->e.documentId().equals(evidence.documentId())&&e.version().equals(evidence.version())&&e.start()<evidence.end()&&e.end()>evidence.start()).map(Evidence::id).toList());
                else read.put("evidence",evidence);
                read.put("range",Map.of("start",window.start(),"end",window.end()));read.put("truncated",window.truncated());
                read.put("nextOffset",window.nextOffset()==null?-1:window.nextOffset());read.put("totalChars",window.totalChars());read.put("newChars",c.evidenceChars-before);
                read.put("nextRead",window.nextOffset()==null?Map.of():Map.of("documentId",window.documentId(),"version",window.version(),"offset",window.nextOffset(),"maxChars",length));
                read.put("readingNote",(c.evidenceChars==before?"本次读取没有新增内容，全文已包含在此前返回的证据窗口中。不要重复阅读，应使用已有证据回答或只查明确缺失的信息。":"")+(window.truncated()?"需要后续原文才使用nextRead参数继续；当前内容已足够回答就提交。相同参数不会翻页。":"此读取范围已到结尾，不要重复读取相同位置。可直接引用已返回的原文段落。"));
                result=read;
            }
            case "materials.read" -> {
                var windows=MaterialEvidence.read(c,text(input,"materialId"),input.path("offset").asInt(0),input.path("maxChars").asInt(6000));
                var accepted=new ArrayList<Evidence>();
                for(var evidence:windows)if(c.evidence.containsKey(evidence.id())||c.evidenceChars+evidence.content().length()<=60000){addEvidence(c,evidence);accepted.add(evidence);}
                result=Map.of("evidence",accepted,"status",accepted.isEmpty()?"budget_exhausted":"ok");
            }
            case "incident.read" -> {
                if(c.incident==null)result=Map.of("status","missing_input","message","本次没有绑定现场，请让用户提交告警或日志");
                else {
                    int observationChars=0;
                    for(var entry:c.incident.observations().entrySet()) {
                        if(observationChars+entry.getValue().length()>16000||c.evidenceChars+entry.getValue().length()>60000)break;
                        addEvidence(c,new Evidence(entry.getKey(),"",c.incident.id(),c.incident.scenarioName(),entry.getKey(),0,entry.getValue().length(),entry.getValue(),"observation"));
                        observationChars+=entry.getValue().length();
                    }
                    result=Map.of("snapshotId",c.incident.id(),"service",c.incident.service(),"observations",c.evidence.values().stream().filter(e->e.kind().equals("observation")).toList(),"notice","本次固定输入快照；可能仅显示部分材料，请通过材料目录按需读取。用户材料未经独立核实；重复调用不会产生新观测");
                }
            }
            default -> {
                var remote=mcp.list().stream().flatMap(connection->connection.tools().stream()).filter(t->t.id().equals(tool.id())).findFirst().orElseThrow(()->PlatformCatalog.missing("工具"));
                result=mcp.call(remote,json.convertValue(input,Map.class));
                if(!"error".equals(json.valueToTree(result).path("status").asText())) {
                    String content=catalog.encode(result);int available=Math.max(0,Math.min(20000,60000-c.evidenceChars));
                    if(content.length()>available)content=content.substring(0,available);
                    var evidence=new Evidence("M-"+KnowledgeFiles.digest(key+content).substring(0,20),"","",tool.title(),tool.title(),0,content.length(),content,"tool");
                    addEvidence(c,evidence);result=Map.of("evidence",evidence,"truncated",catalog.encode(result).length()>available);
                }
            }
        }
        String status=json.valueToTree(result).path("status").asText("ok");
        if(cacheable&&!tool.id().startsWith("mcp:")
                &&!Set.of("error","degraded","budget_exhausted","missing_input").contains(status))c.cache.put(key,result);
        return result;
    }
    static Map<String,Object> currentTime(JsonNode input,Clock clock) {
        String requested=input.path("timezone").asText(DEFAULT_TIME_ZONE).strip();
        if(requested.isEmpty())requested=DEFAULT_TIME_ZONE;
        if(requested.length()>100)throw PlatformCatalog.bad("时区名称过长");
        final ZoneId zone;
        try{zone=ZoneId.of(requested);}catch(DateTimeException error){throw PlatformCatalog.bad("无效时区，请使用 IANA 名称，例如 Asia/Hong_Kong 或 UTC");}
        ZonedDateTime now=ZonedDateTime.now(clock).withZoneSameInstant(zone).withNano(0);
        Map<String,Object> value=new LinkedHashMap<>();
        value.put("timezone",zone.getId());
        value.put("isoDateTime",now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        value.put("localDate",now.toLocalDate().toString());
        value.put("localTime",now.toLocalTime().toString());
        value.put("utcOffset",now.getOffset().getId().equals("Z")?"+00:00":now.getOffset().getId());
        value.put("unixTimestamp",now.toEpochSecond());
        return value;
    }
    private static List<PlatformChunk> relevantDocuments(List<PlatformChunk> chunks){
        if(chunks==null||chunks.isEmpty()||chunks.stream().noneMatch(chunk->chunk.rerankScore()!=null))return chunks==null?List.of():chunks;
        return chunks.stream().filter(chunk->chunk.rerankScore()!=null&&chunk.rerankScore()>=0.15).toList();
    }
    private List<Evidence> remember(List<PlatformChunk> chunks,Context c) throws Exception {
        List<Evidence> result=new ArrayList<>();
        for(var evidence:knowledge.contextWindows(c.scope,chunks)) {
            if(EvidenceContext.add(c,evidence,60000)){c.locatedDocumentIds.add(evidence.documentId());result.add(evidence);}
        }
        return List.copyOf(result);
    }
    private void addEvidence(Context context,Evidence evidence){EvidenceContext.add(context,evidence,60000);}
    private static String text(JsonNode input,String field){return PlatformCatalog.required(input.path(field).asText(null),8000,field);}
    private String searchMode(JsonNode input,Context context) {
        if(input.hasNonNull("mode")&&!input.path("mode").asText().isBlank())return input.path("mode").asText();
        Set<String> configured=new LinkedHashSet<>();
        for(String id:context.scope.knowledgeBaseIds())configured.add(catalog.knowledgeBase(id).retrievalMode());
        return configured.size()==1?configured.iterator().next():"hybrid";
    }
    private void validateInput(Tool tool,JsonNode input) {
        if(input==null||!input.isObject())throw PlatformCatalog.bad("工具参数须是JSON对象");
        var schema=json.valueToTree(tool.schema());
        for(var required:schema.path("required"))if(!input.hasNonNull(required.asText()))throw PlatformCatalog.bad("工具缺少参数: "+required.asText());
        var names=input.fieldNames();
        while(names.hasNext()) {
            String name=names.next();var property=schema.path("properties").path(name);var value=input.get(name);
            if(property.isMissingNode()&&schema.path("additionalProperties").isBoolean()&&!schema.path("additionalProperties").asBoolean())throw PlatformCatalog.bad("未知工具参数: "+name);
            String type=property.path("type").asText();
            if(type.equals("integer")&&!value.isIntegralNumber()||type.equals("string")&&!value.isTextual()||type.equals("array")&&!value.isArray())throw PlatformCatalog.bad("工具参数类型错误: "+name);
            if(property.has("enum")){boolean match=false;for(var option:property.get("enum"))match|=option.equals(value);if(!match)throw PlatformCatalog.bad("工具参数值无效: "+name);}
            if(value.isNumber()&&(property.has("minimum")&&value.doubleValue()<property.get("minimum").doubleValue()||property.has("maximum")&&value.doubleValue()>property.get("maximum").doubleValue()))throw PlatformCatalog.bad("工具参数超出范围: "+name);
        }
    }
    private static Map<String,Object> string(String description){return Map.of("type","string","description",description);}
    static boolean isSearch(Tool tool){return Set.of("knowledge.search","documents.search","documents.find").contains(tool.id());}
    static int searchLimit(Context context){return context.openQuestion?3:4;}
    private static Map<String,Object> schema(Map<String,Object> properties,String... required){return Map.of("type","object","properties",properties,"required",List.of(required),"additionalProperties",false);}
    private static Tool tool(String id,String name,String title,String description,Map<String,Object> schema){return new Tool(id,name,title,description,schema,"内置",true);}
    private static List<Tool> builtins(){return List.of(
            tool("system.current_time","current_datetime","当前日期时间","查询真实当前时间；涉及“现在”或“今天”时使用。",schema(Map.of("timezone",string("可选 IANA 时区，例如 Asia/Shanghai 或 UTC")))),
            tool("knowledge.list","list_knowledge_sources","列出授权文档","列出当前 Agent 授权的知识库与数据集名称，以及文档、版本和长度。name 是显示名称，id 是内部标识；最多返回100篇文档，truncated 表示未列完。不读取正文，也不扩大知识范围。",schema(Map.of())),
            tool("knowledge.search","search_knowledge","知识检索","按问题找相关文档片段；支持向量、全文 BM25 或混合检索。省略 mode 时使用知识库配置，明确的精确术语场景可覆盖。命中后按需读取原文。",schema(Map.of("query",string("保留专有词、错误码和否定条件"),"mode",Map.of("type","string","enum",List.of("semantic","keyword","hybrid"),"description","可选覆盖；省略时使用知识库默认召回模式")),"query")),
            tool("documents.find","find_documents","文件定位","按文件名或路径定位文档；不做语义检索。",schema(Map.of("query",string("文件名或路径片段")),"query")),
            tool("documents.search","search_document_text","全文检索（BM25）","在文档正文中查精确术语或错误码；可限定某篇文档。",schema(Map.of("query",string("问题、术语或错误码"),"documentId",string("可选，限定文档")),"query")),
            tool("documents.sections","list_document_sections","查看章节","列出文档章节，仅用于较大文档定位。",schema(Map.of("documentId",string("文档ID"),"version",string("文档版本")),"documentId","version")),
            tool("documents.read","read_document","读取原文","按明确位置读取原文。检索已提供正文窗口，足够回答时不用再读。读取后续内容使用返回的nextRead参数；重复相同参数不会翻页。",schema(Map.of("documentId",string("文档ID"),"version",string("版本"),"sectionId",string("可选章节ID；offset须位于该章节"),"offset",Map.of("type","integer","minimum",0,"description","原文绝对字符位置。根据检索start/end、章节start或nextRead.offset选择；不要重复已读位置。"),"maxChars",Map.of("type","integer","minimum",1,"maximum",20000,"description","读取字符数，默认6000，最大20000。")),"documentId","version")),
            materialTool());}
    static Tool incidentTool(){return tool("incident.read","read_incident","读取现场","读取本次固定告警和日志。",schema(Map.of()));}
    private static Tool materialTool(){return tool("materials.read","read_material","读取本次材料","读取本轮提交的告警、日志或附件；只读用户材料。",schema(Map.of("materialId",string("材料ID"),"offset",Map.of("type","integer","minimum",0),"maxChars",Map.of("type","integer","minimum",1,"maximum",12000)),"materialId"));}
}
