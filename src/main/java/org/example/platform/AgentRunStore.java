package org.example.platform;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

/** Durable run state; session ownership and one active run are enforced before scheduling work. */
@Service
public class AgentRunStore {
    public record Session(String id,String agentId,int agentVersion,String ownerId,String title,String updatedAt) {}
    public record Run(String id,String sessionId,String agentId,int agentVersion,String status,String inputJson,String contextJson,String resultJson,String error,String createdAt,String finishedAt) {}
    public record Event(int sequence,String type,Object data,String createdAt) {}
    private final JdbcTemplate db;private final PlatformCatalog catalog;
    public AgentRunStore(JdbcTemplate db,PlatformCatalog catalog){this.db=db;this.catalog=catalog;}
    private static final Set<String> ACTIVE=Set.of("queued","running","reviewing");
    public static boolean active(String status){return ACTIVE.contains(status);}
    public Session session(String id,String owner) {
        var values=db.query("SELECT id,agent_id,agent_version,owner_id,title,updated_at FROM agent_sessions WHERE id=? AND owner_id=?",(r,n)->new Session(r.getString(1),r.getString(2),r.getInt(3),r.getString(4),r.getString(5),r.getTimestamp(6).toInstant().toString()),id,owner);
        if(values.isEmpty())throw PlatformCatalog.missing("会话");return values.get(0);
    }
    public List<Session> sessions(String owner,String agentId) { return sessions(owner,agentId,0,50); }
    public Session writableSession(String id,String owner){
        var session=session(id,owner);
        if(catalog.agent(session.agentId(),session.agentVersion()).config().schemaVersion()!=2)
            throw PlatformCatalog.bad("升级前会话仅供查看，请新建会话");
        return session;
    }
    public List<Session> sessions(String owner,String agentId,int offset,int limit) {
        if(offset<0||limit<1||limit>100)throw PlatformCatalog.bad("分页参数无效");
        return db.query("SELECT id,agent_id,agent_version,owner_id,title,updated_at FROM agent_sessions WHERE owner_id=? AND agent_id=? AND origin='user' ORDER BY updated_at DESC,id LIMIT ? OFFSET ?",(r,n)->new Session(r.getString(1),r.getString(2),r.getInt(3),r.getString(4),r.getString(5),r.getTimestamp(6).toInstant().toString()),owner,agentId,limit,offset);
    }
    public Session openSession(String owner,String agentId,String id,String question) {
        if(id!=null&&!id.isBlank()) {
            var session=writableSession(id,owner);if(!session.agentId().equals(agentId))throw PlatformCatalog.bad("会话不属于当前智能体");return session;
        }
        return openVersionedSession(owner,agentId,null,question);
    }
    public Session openVersionedSession(String owner,String agentId,Integer version,String question) {
        return openVersionedSession(owner,agentId,version,question,"user");
    }
    public Session openVersionedSession(String owner,String agentId,Integer version,String question,String origin) {
        if(!Set.of("user","debug","evaluation").contains(origin))throw PlatformCatalog.bad("会话来源无效");
        var agent=catalog.agent(agentId,version);if(!agent.enabled())throw PlatformCatalog.bad("智能体已停用");
        if(agent.config().schemaVersion()!=2)throw PlatformCatalog.bad("升级前会话仅供查看，请新建会话");
        String key=UUID.randomUUID().toString();String title=question.substring(0,Math.min(80,question.length()));
        db.update("INSERT INTO agent_sessions(id,agent_id,agent_version,owner_id,title,origin) VALUES(?,?,?,?,?,?)",key,agent.id(),agent.version(),owner,title,origin);
        return session(key,owner);
    }
    private static final String RUNS="SELECT id,session_id,agent_id,agent_version,status,input_json,context_json,result_json,error_message,created_at,finished_at FROM agent_runs ";
    private static final org.springframework.jdbc.core.RowMapper<Run> MAPPER=(r,n)->new Run(r.getString(1),r.getString(2),r.getString(3),r.getInt(4),r.getString(5),r.getString(6),r.getString(7),r.getString(8),r.getString(9),r.getTimestamp(10).toInstant().toString(),r.getTimestamp(11)==null?null:r.getTimestamp(11).toInstant().toString());
    public Run get(String id) {return db.query(RUNS+"WHERE id=?",MAPPER,id).stream().findFirst().orElseThrow(()->PlatformCatalog.missing("执行记录"));}
    public Run owned(String id,String owner){var run=get(id);session(run.sessionId(),owner);return run;}
    public List<Run> history(String sessionId,String owner){session(sessionId,owner);return db.query(RUNS+"WHERE session_id=? ORDER BY created_at,id",MAPPER,sessionId);}
    public String origin(String sessionId){return db.queryForObject("SELECT origin FROM agent_sessions WHERE id=?",String.class,sessionId);}
    public List<Run> recent(){return db.query(RUNS+"ORDER BY created_at DESC LIMIT 200",MAPPER);}
    @Transactional
    public Run create(Session session,Object input,Object context) {
        db.queryForList("SELECT id FROM agent_sessions WHERE id=? FOR UPDATE",String.class,session.id());
        if(db.queryForObject("SELECT COUNT(*) FROM agent_runs WHERE session_id=? AND status IN ('queued','running','reviewing')",Integer.class,session.id())>0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"当前会话已有运行中的任务，请完成或停止后继续");
        String id=UUID.randomUUID().toString();
        if("新会话".equals(session.title()) && input instanceof AgentRuntime.Input request)
            db.update("UPDATE agent_sessions SET title=? WHERE id=?",request.question().substring(0,Math.min(80,request.question().length())),session.id());
        db.update("INSERT INTO agent_runs(id,session_id,agent_id,agent_version,status,input_json,context_json) VALUES(?,?,?,?,'queued',?,?)",id,session.id(),session.agentId(),session.agentVersion(),catalog.encode(input),catalog.encode(context));
        db.update("UPDATE agent_sessions SET updated_at=CURRENT_TIMESTAMP WHERE id=?",session.id());return get(id);
    }
    public boolean stage(String id,String status) {
        if(!Set.of("running","reviewing").contains(status))throw new IllegalArgumentException("invalid running stage");
        return db.update("UPDATE agent_runs SET status=? WHERE id=? AND status IN ('queued','running','reviewing')",status,id)==1;
    }
    @Transactional
    public boolean finish(String id,String status,Object result,String error) {
        if(active(status))throw new IllegalArgumentException("terminal status required");
        boolean changed=db.update("UPDATE agent_runs SET status=?,result_json=?,error_message=?,finished_at=CURRENT_TIMESTAMP WHERE id=? AND status IN ('queued','running','reviewing')",status,result==null?null:catalog.encode(result),error,id)==1;
        if(changed)event(id,"terminal",Map.of("status",status,"message",Objects.toString(error,"")));
        return changed;
    }
    @Transactional
    public Event event(String id,String type,Object payload) {
        db.queryForList("SELECT id FROM agent_runs WHERE id=? FOR UPDATE",String.class,id);
        int sequence=db.queryForObject("SELECT COALESCE(MAX(sequence),0)+1 FROM run_events WHERE run_id=?",Integer.class,id);
        db.update("INSERT INTO run_events(run_id,sequence,event_type,payload_json) VALUES(?,?,?,?)",id,sequence,type,catalog.encode(payload));
        return new Event(sequence,type,payload,java.time.Instant.now().toString());
    }
    public List<Event> events(String id,int after) {
        return db.query("SELECT sequence,event_type,payload_json,created_at FROM run_events WHERE run_id=? AND sequence>? ORDER BY sequence LIMIT 200",(r,n)->new Event(r.getInt(1),r.getString(2),catalog.decode(r.getString(3),Object.class),r.getTimestamp(4).toInstant().toString()),id,Math.max(0,after));
    }
}
