package org.example.platform;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.*;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** MCP protocol implementation stays in the SDK. The platform owns registration, enablement and tool binding. */
@Service
public class McpConnections {
    public record Connection(String id,String name,String endpoint,String credentialEnv,boolean enabled,String status,List<RemoteTool> tools,String error) {}
    public record RemoteTool(String id,String connectionId,String remoteName,String name,String description,Map<String,Object> inputSchema,boolean readOnly) {}
    private final JdbcTemplate db;private final ObjectMapper json;
    private final Map<String,McpSyncClient> clients=new ConcurrentHashMap<>();
    public McpConnections(JdbcTemplate db,ObjectMapper json){this.db=db;this.json=json;}
    public List<Connection> list(){return db.query("SELECT id,name,endpoint,credential_env,enabled,status,tools_json,error_message FROM mcp_connections ORDER BY name,id",(r,n)->new Connection(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getBoolean(5),r.getString(6),decodeTools(r.getString(7)),r.getString(8)));}
    public Connection get(String id){return list().stream().filter(c->c.id().equals(id)).findFirst().orElseThrow(()->PlatformCatalog.missing("MCP连接"));}
    public synchronized Connection save(String id,String name,String endpoint,String credentialEnv,boolean enabled) {
        name=PlatformCatalog.required(name,160,"连接名称");endpoint=PlatformCatalog.required(endpoint,2000,"MCP地址");URI uri;
        try{uri=URI.create(endpoint);}catch(Exception e){throw PlatformCatalog.bad("MCP地址无效");}
        if(!Set.of("http","https").contains(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getFragment()!=null)throw PlatformCatalog.bad("请使用不含凭据的HTTP或HTTPS端点");
        credentialEnv=credentialEnv==null?"":credentialEnv.strip();
        if(!credentialEnv.isEmpty()&&!credentialEnv.matches("[A-Z][A-Z0-9_]{1,119}"))throw PlatformCatalog.bad("凭据使用服务端环境变量名称");
        String key=id==null?UUID.randomUUID().toString():id;
        if(id==null)db.update("INSERT INTO mcp_connections(id,name,endpoint,credential_env,enabled) VALUES(?,?,?,?,?)",key,name,endpoint,credentialEnv,enabled);
        else {get(id);close(id);db.update("UPDATE mcp_connections SET name=?,endpoint=?,credential_env=?,enabled=?,status='UNTESTED',tools_json='[]',error_message=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?",name,endpoint,credentialEnv,enabled,id);}
        return get(key);
    }
    public synchronized Connection discover(String id) {
        try {
            var client=client(id);var connection=get(id);List<RemoteTool> tools=new ArrayList<>();String cursor=null;Set<String> cursors=new HashSet<>();
            do {
                var page=cursor==null?client.listTools():client.listTools(cursor);
                for(var tool:page.tools()) {
                    if(tools.size()>=100)throw new IllegalStateException("工具数量超过100，请缩小服务范围");
                    Map<String,Object> schema=json.convertValue(tool.inputSchema(),new TypeReference<Map<String,Object>>(){});
                    schema.values().removeIf(Objects::isNull);
                    // A pinned run must never invoke a replacement endpoint or a changed tool contract.
                    String local="mcp_"+org.example.service.KnowledgeFiles.digest(id+":"+connection.endpoint()+":"+connection.credentialEnv()+":"+json.writeValueAsString(tool)).substring(0,20);
                    tools.add(new RemoteTool("mcp:"+id+":"+local,id,tool.name(),local,Objects.toString(tool.description(),tool.name()),schema,
                            tool.annotations()!=null&&Boolean.TRUE.equals(tool.annotations().readOnlyHint())));
                }
                cursor=page.nextCursor();
                if(cursor!=null&&!cursors.add(cursor))throw new IllegalStateException("MCP分页游标重复");
            }while(cursor!=null&&!cursor.isBlank());
            db.update("UPDATE mcp_connections SET status='CONNECTED',tools_json=?,error_message=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?",json.writeValueAsString(tools),id);
        }catch(Exception error){close(id);db.update("UPDATE mcp_connections SET status='ERROR',error_message=?,updated_at=CURRENT_TIMESTAMP WHERE id=?","MCP连接或发现失败: "+error.getClass().getSimpleName(),id);}
        return get(id);
    }
    public Object call(RemoteTool tool,Map<String,Object> arguments) {
        Connection connection=get(tool.connectionId());
        if(!connection.enabled()||!connection.tools().stream().anyMatch(t->t.id().equals(tool.id())))throw PlatformCatalog.bad("MCP工具未启用或已改变");
        if(!tool.readOnly())throw PlatformCatalog.bad("首版仅执行声明为只读的MCP工具");
        var result=client(tool.connectionId()).callTool(new McpSchema.CallToolRequest(tool.remoteName(),arguments));
        Map<String,Object> output=new LinkedHashMap<>();output.put("status",Boolean.TRUE.equals(result.isError())?"error":"ok");
        output.put("content",result.content());if(result.structuredContent()!=null)output.put("structuredContent",result.structuredContent());return output;
    }
    private synchronized McpSyncClient client(String id) {
        var existing=clients.get(id);if(existing!=null)return existing;
        Connection c=get(id);if(!c.enabled())throw PlatformCatalog.bad("MCP连接已停用");URI endpoint=URI.create(c.endpoint());
        String origin=endpoint.getScheme()+"://"+endpoint.getRawAuthority();
        String path=Objects.toString(endpoint.getRawPath(),"/mcp")+(endpoint.getRawQuery()==null?"":"?"+endpoint.getRawQuery());
        var transport=HttpClientStreamableHttpTransport.builder(origin).endpoint(path)
                .clientBuilder(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER));
        if(c.credentialEnv()!=null&&!c.credentialEnv().isBlank()) {
            String token=System.getenv(c.credentialEnv());if(token==null||token.isBlank())throw new IllegalStateException("MCP凭据环境变量未配置");
            transport.customizeRequest(builder->builder.header("Authorization","Bearer "+token));
        }
        var client=McpClient.sync(transport.build()).requestTimeout(Duration.ofSeconds(20)).initializationTimeout(Duration.ofSeconds(10))
                .clientInfo(new McpSchema.Implementation("knowledge-agent-platform","1.0")).build();
        try{client.initialize();clients.put(id,client);return client;}catch(Exception error){client.close();throw error;}
    }
    private List<RemoteTool> decodeTools(String value){try{return json.readValue(value,new TypeReference<List<RemoteTool>>(){});}catch(Exception e){throw new IllegalStateException("工具目录数据无效",e);}}
    private void close(String id){var old=clients.remove(id);if(old!=null)old.close();}
    @PreDestroy public void close(){new ArrayList<>(clients.keySet()).forEach(this::close);}
}
