package org.example.platform;

import org.example.service.ConversationStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** Older installations had no owners. Only an administrator may explicitly claim their local history. */
@RestController
@RequestMapping("/api/platform/admin/legacy-history")
public class LegacyHistoryImport {
    private final ConversationStore legacy;private final AgentRunStore runs;private final PlatformCatalog catalog;
    private final KnowledgeSearch search;private final AgentToolRegistry tools;private final PlatformIdentity identity;private final JdbcTemplate db;
    public LegacyHistoryImport(ConversationStore legacy,AgentRunStore runs,PlatformCatalog catalog,KnowledgeSearch search,AgentToolRegistry tools,PlatformIdentity identity,JdbcTemplate db){this.legacy=legacy;this.runs=runs;this.catalog=catalog;this.search=search;this.tools=tools;this.identity=identity;this.db=db;}
    @GetMapping public Object list()throws Exception{return legacy.sessionIds();}
    public record Import(String id,String title,List<Map<String,String>> messages){}
    @PostMapping @Transactional
    public Object restore(Authentication user,@RequestBody Import input)throws Exception {
        String owner=identity.userId(user);String alias=PlatformCatalog.required(input.id(),100,"旧会话ID");
        ConversationStore.sessionId(alias);
        var exists=db.queryForList("SELECT session_id FROM legacy_session_aliases WHERE owner_id=? AND alias=?",String.class,owner,alias);
        if(!exists.isEmpty())return Map.of("sessionId",exists.get(0),"action","existing");
        var stored=legacy.history(alias);var diagnosis=legacy.diagnosis(alias,null);
        var imported=input.messages()==null?List.<Map<String,String>>of():input.messages();
        if(imported.size()>100)throw PlatformCatalog.bad("一次最多导入100条历史消息");
        List<Map<String,String>> messages=stored.isEmpty()?imported:stored;
        if(diagnosis==null&&messages.isEmpty())throw PlatformCatalog.bad("该会话没有可恢复的报告或消息");
        String title=input.title()==null?"恢复的旧会话 "+alias:PlatformCatalog.required(input.title(),80,"历史标题");
        var session=runs.openSession(owner,"oncall",null,title);var config=catalog.agent("oncall",session.agentVersion()).config();
        var incident=diagnosis==null?null:diagnosis.snapshot();
        var context=new AgentRuntime.Context(search.scope(config.knowledgeBaseIds()),incident,tools.allowed(config).stream().filter(t->incident!=null||!t.id().equals("incident.read")).toList(),List.of(),List.of());
        List<AgentToolRegistry.Evidence> evidence=new ArrayList<>();
        if(incident!=null)incident.observations().forEach((id,text)->evidence.add(new AgentToolRegistry.Evidence(id,"",incident.id(),incident.scenarioName(),id,0,text.length(),text,"observation")));
        if(diagnosis!=null&&diagnosis.documents()!=null)diagnosis.documents().forEach(d->evidence.add(new AgentToolRegistry.Evidence(d.id(),"","legacy",d.sourceFile(),d.title(),0,d.content().length(),d.content(),"document")));
        List<String> notices=List.of("从升级前记录恢复，未经过当前版本重新审校；引用和现场沿用原存档。后续检索可补充当前绑定资料。");
        if(diagnosis!=null) {
            var a=diagnosis.analysis();var answer=a==null?new AgentAnswerService.Answer(List.of(),List.of(),List.of()):new AgentAnswerService.Answer(a.findings(),a.actions(),a.missingEvidence());
            archive(session,context,Objects.toString(incident.symptoms(),"恢复旧诊断报告"),Objects.toString(diagnosis.report(),"旧报告正文缺失"),answer,evidence,notices);
        }
        String question="恢复的历史对话";
        for(var message:messages) {
            String text=message.getOrDefault("content","");if(text.length()>100000)throw PlatformCatalog.bad("历史消息过长");if(text.isBlank())continue;
            String role=message.getOrDefault("role",message.getOrDefault("type","assistant"));
            if(role.equals("user")){question=text.substring(0,Math.min(8000,text.length()));continue;}
            if(!role.equals("assistant"))continue;
            archive(session,context,question,text,new AgentAnswerService.Answer(List.of(),List.of(),List.of()),evidence,notices);
        }
        db.update("INSERT INTO legacy_session_aliases(owner_id,alias,session_id) VALUES(?,?,?)",owner,alias,session.id());
        return Map.of("sessionId",session.id(),"action","imported");
    }
    private void archive(AgentRunStore.Session session,AgentRuntime.Context context,String question,String text,AgentAnswerService.Answer answer,List<AgentToolRegistry.Evidence> evidence,List<String> notices) {
        var run=runs.create(session,new AgentRuntime.Input("oncall",session.id(),question,context.incident()==null?null:context.incident().scenarioId(),context.incident()!=null),context);
        runs.finish(run.id(),"archived",new AgentRuntime.Result("archived",text,answer,context.incident(),List.copyOf(evidence),List.of(),notices,0,0,"历史记录（模型未记录）"),null);
    }
}
