package org.example.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.assertj.core.api.Assertions.*;

class McpConnectionsTest {
    @Test void sdkInitializesDiscoversAndInvokesReadOnlyRemoteTool()throws Exception {
        var json=new ObjectMapper();List<String> calls=new CopyOnWriteArrayList<>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/mcp",exchange->{
            if(!exchange.getRequestMethod().equals("POST")){exchange.sendResponseHeaders(405,-1);exchange.close();return;}
            var request=json.readTree(exchange.getRequestBody());String method=request.path("method").asText();calls.add(method);
            if(!request.has("id")){exchange.sendResponseHeaders(202,-1);exchange.close();return;}
            Object result=switch(method){
                case "initialize" -> Map.of("protocolVersion","2025-03-26","capabilities",Map.of("tools",Map.of()),"serverInfo",Map.of("name","acceptance-fixture","version","1"));
                case "tools/list" -> Map.of("tools",List.of(Map.of("name","lookup_release","description","Read the release note","inputSchema",Map.of("type","object","properties",Map.of("version",Map.of("type","string")),"required",List.of("version")),"annotations",Map.of("readOnlyHint",true)),Map.of("name","delete_release","description","Write operation","inputSchema",Map.of("type","object"),"annotations",Map.of("readOnlyHint",false))));
                case "tools/call" -> Map.of("content",List.of(Map.of("type","text","text","release-42: retrieval scope filtering enabled")),"isError",false);
                default -> Map.of();
            };
            byte[] body=json.writeValueAsBytes(Map.of("jsonrpc","2.0","id",request.get("id"),"result",result));
            exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        var data=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__platform_catalog.sql"), new ClassPathResource("db/migration/V5__session_origin.sql")).execute(data);
        var mcp=new McpConnections(new JdbcTemplate(data),json);
        try {
            var connection=mcp.save(null,"Fixture","http://127.0.0.1:"+server.getAddress().getPort()+"/mcp","",true);
            connection=mcp.discover(connection.id());assertThat(connection.status()).isEqualTo("CONNECTED");assertThat(connection.tools()).hasSize(2);
            var lookup=connection.tools().stream().filter(McpConnections.RemoteTool::readOnly).findFirst().orElseThrow();
            assertThat(json.writeValueAsString(mcp.call(lookup,Map.of("version","42")))).contains("release-42");
            var write=connection.tools().stream().filter(t->!t.readOnly()).findFirst().orElseThrow();
            assertThatThrownBy(()->mcp.call(write,Map.of())).hasMessageContaining("只读");
            assertThat(calls).contains("initialize","notifications/initialized","tools/list","tools/call");
        }finally{mcp.close();server.stop(0);}
    }
}
