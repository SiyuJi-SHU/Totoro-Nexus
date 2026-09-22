package org.example.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.IncidentSnapshot;
import org.example.service.KnowledgeFiles;
import java.util.*;

/** User materials stay data; every read window has an immutable source identity. */
final class MaterialEvidence {
    static final int WINDOW=2000;
    static IncidentSnapshot incident(IncidentSnapshot previous,List<AgentRuntime.Context.AttachmentContent> materials,String question,ObjectMapper json) {
        try {
            var logs=json.createArrayNode();var alerts=json.createArrayNode();
            boolean mock=previous!=null&&!"user-materials".equals(previous.scenarioId());
            if(mock){previous.alerts().forEach(alerts::add);for(var log:previous.logs())if(!log.has("materialId"))logs.add(log);}
            for(var material:materials)for(int offset=0;offset<material.content().length();offset+=WINDOW) {
                logs.addObject().put("message",material.content().substring(offset,Math.min(offset+WINDOW,material.content().length())))
                    .put("materialId",material.id()).put("sourceFile",material.filename()).put("contentHash",material.hash())
                    .put("offset",offset).put("data_source","user_submitted_unverified");
            }
            String hash=KnowledgeFiles.digest(logs.toString()+alerts.toString());
            return new IncidentSnapshot("material-"+hash.substring(0,24),mock?previous.scenarioId():"user-materials",
                mock?previous.scenarioName().replace(" + 补充材料","")+" + 补充材料":"用户提交的现场材料",question,
                json.createObjectNode().set("alerts",alerts).toString(),json.createObjectNode().set("logs",logs).toString(),
                previous!=null&&previous.id().equals("material-"+hash.substring(0,24))?previous.capturedAt():java.time.Instant.now().toString());
        }catch(Exception error){throw new IllegalArgumentException("材料无法建立现场快照",error);}
    }
    static List<AgentToolRegistry.Evidence> read(AgentToolRegistry.Context context,String id,int offset,int maxChars) {
        var material=context.materials.stream().filter(m->m.id().equals(id)).findFirst().orElseThrow(()->PlatformCatalog.missing("本次材料"));
        if(offset<0||offset>=material.content().length())throw PlatformCatalog.bad("材料读取位置无效");
        List<AgentToolRegistry.Evidence> result=new ArrayList<>();
        if(context.diagnostic&&context.incident!=null) {
            int index=0;var observations=context.incident.observations();
            for(var log:context.incident.logs()) {
                String evidenceId="L"+(++index);int start=log.path("offset").asInt(-1);
                if(id.equals(log.path("materialId").asText())&&start+WINDOW>offset&&start<offset+maxChars) {
                    String content=observations.get(evidenceId);
                    result.add(new AgentToolRegistry.Evidence(evidenceId,id,material.hash(),material.filename(),"用户提交材料",start,Math.min(start+WINDOW,material.content().length()),content,"observation"));
                }
            }
        }else {
            int end=Math.min(material.content().length(),offset+maxChars);
            result.add(new AgentToolRegistry.Evidence("U-"+KnowledgeFiles.digest(id+material.hash()+offset+end).substring(0,20),id,material.hash(),material.filename(),"本次附件",offset,end,material.content().substring(offset,end),"attachment"));
        }
        return result;
    }
}
