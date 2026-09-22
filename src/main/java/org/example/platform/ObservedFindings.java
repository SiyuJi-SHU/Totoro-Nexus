package org.example.platform;

import org.example.dto.*;
import java.util.*;

/** The model chooses which observations matter; their wording and values come directly from the immutable snapshot. */
final class ObservedFindings {
    static List<GroundedAnalysis.Finding> merge(List<GroundedAnalysis.Finding> findings,IncidentSnapshot incident) {
        Set<String> used=new HashSet<>();List<GroundedAnalysis.Finding> result=new ArrayList<>();
        for(var finding:findings) {
            if(!"observation".equals(finding.certainty())){result.add(finding);continue;}
            var refs=finding.citations().stream().filter(c->!used.contains(c.id())).toList();if(refs.isEmpty())continue;
            var rendered=literal(new GroundedAnalysis.Finding(finding.text(),finding.certainty(),refs),incident);
            rendered.citations().forEach(c->used.add(c.id()));result.add(rendered);
        }
        return List.copyOf(result);
    }
    static GroundedAnalysis.Finding literal(GroundedAnalysis.Finding finding,IncidentSnapshot incident) {
        if(!"observation".equals(finding.certainty()))return finding;
        Map<String,String> sources=incident.observations();Map<String,String> lines=new LinkedHashMap<>();
        for(var ref:finding.citations())if(ref.id().matches("L\\d+")&&sources.containsKey(ref.id())) {
            int index=Integer.parseInt(ref.id().substring(1))-1;
            if(index<incident.logs().size())lines.put(ref.id(),incident.logs().get(index).path("message").asText(sources.get(ref.id())));
        }
        boolean logs=!lines.isEmpty();
        if(!logs)for(var ref:finding.citations())if(ref.id().matches("A\\d+")&&sources.containsKey(ref.id())) {
            int index=Integer.parseInt(ref.id().substring(1))-1;
            if(index<incident.alerts().size()){var a=incident.alerts().get(index);lines.put(ref.id(),a.path("description").asText(a.path("alert_name").asText(incident.scenarioName())));}
        }
        if(lines.isEmpty())return finding;
        var citations=lines.keySet().stream().map(id->new GroundedAnalysis.Citation(id,sources.get(id))).toList();
        return new GroundedAnalysis.Finding((incident.scenarioId().equals("user-materials")?"用户提交记录（未经独立核实）：\n":logs?"日志原文：\n":"告警记录：")+String.join("\n",lines.values()),"observation",citations);
    }
}
