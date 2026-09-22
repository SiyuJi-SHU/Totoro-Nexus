package org.example.platform;

import com.fasterxml.jackson.databind.JsonNode;
import org.example.dto.*;
import java.util.*;

/** Diagnosis publishes literal procedure/causal passages, so a paraphrase cannot change their conditions or order. */
final class DiagnosticSourceRendering {
    static AgentAnswerService.Validated render(AgentAnswerService.Validated checked, JsonNode proposals,JsonNode selection,
                                               Map<String,AgentToolRegistry.Evidence> evidence,IncidentSnapshot incident,boolean diagnosis) {
        List<GroundedAnalysis.Finding> findings=new ArrayList<>();List<GroundedAnalysis.Action> actions=new ArrayList<>();int removed=0;
        for(var finding:checked.answer().findings()) {
            if(!diagnosis||!"hypothesis".equals(finding.certainty())){findings.add(finding);continue;}
            String excerpt=null,selectedQuote=null;
            for(var selected:selection.path("findings"))if(normalize(selected.path("text").asText()).equals(normalize(finding.text()))) {
                int index=selected.path("index").asInt(-1);if(index<0||index>=proposals.path("findings").size())continue;
                for(var source:proposals.path("findings").get(index).path("citations")) {
                    String id=source.path("id").asText();
                    if(id.startsWith("D-"))for(var chosen:finding.citations())if(chosen.id().startsWith("D-")) {
                        excerpt=context(evidence.get(chosen.id()),source.path("quote").asText());if(excerpt!=null){selectedQuote=source.path("quote").asText();break;}
                    }
                    if(excerpt!=null)break;
                }
                break;
            }
            // Show the incident records behind a hypothesis separately from its runbook passage.
            // This also preserves counterevidence when the causal claim must be withdrawn.
            var observed=finding.citations().stream().filter(c->c.id().matches("[AL]\\d+")&&incident.observations().containsKey(c.id())).toList();
            if(!observed.isEmpty())findings.add(ObservedFindings.literal(new GroundedAnalysis.Finding("", "observation", observed),incident));
            if(excerpt==null){removed++;continue;}
            findings.add(new GroundedAnalysis.Finding("本轮选择的待验证线索（原文；尚不能据此确认本次原因）：\n"+Objects.toString(selectedQuote,excerpt)+"\n\n来源上下文与适用条件：\n"+excerpt,"hypothesis",finding.citations()));
        }
        for(var action:checked.answer().actions()) {
            String excerpt=null;
            for(var ref:action.citations()){excerpt=context(evidence.get(ref.id()),ref.quote());if(excerpt!=null)break;}
            if(excerpt==null){removed++;continue;}
            String pre="需先核对原文条件与本次环境；文档示例不等于当前目标。";
            boolean alreadyBound=Objects.toString(action.prerequisites(),"").startsWith("文档中的环境标识（");
            if(alreadyBound)pre=action.prerequisites().split(" 原文适用条件：",2)[0]+pre;
            var literal=new GroundedAnalysis.Action("手册中的操作说明（原文）：\n"+excerpt,action.command(),pre,action.citations());
            actions.add(alreadyBound?literal:IncidentActionTargets.bind(literal,incident));
        }
        Map<String,GroundedAnalysis.Action> unique=new LinkedHashMap<>();
        for(var action:actions){String key=action.text()+action.command()+action.prerequisites();var old=unique.get(key);
            if(old==null)unique.put(key,action);else {var refs=new ArrayList<>(old.citations());for(var ref:action.citations())if(!refs.contains(ref))refs.add(ref);unique.put(key,new GroundedAnalysis.Action(action.text(),action.command(),action.prerequisites(),List.copyOf(refs)));}}
        actions=new ArrayList<>(unique.values());
        List<String> missing=new ArrayList<>(checked.answer().missingEvidence());
        if(removed>0)missing.add("部分线索或操作未能取得完整且简短的原文条件，需补读对应章节");
        return new AgentAnswerService.Validated(new AgentAnswerService.Answer(diagnosis?ObservedFindings.merge(findings,incident):List.copyOf(findings),List.copyOf(actions),List.copyOf(missing),checked.answer().contradictions()),checked.rejectedItems()+removed,checked.notices());
    }
    private static String normalize(String text){return text.replace("`","").replaceAll("\\s+"," ").strip();}
    static String context(AgentToolRegistry.Evidence evidence,String quote) {
        if(evidence==null||!"document".equals(evidence.kind()))return null;
        String source=evidence.content().replace("\r\n","\n");var resolved=EvidenceQuotes.resolve(source,quote);if(resolved.isEmpty())return null;
        String literal=resolved.get();int at=source.indexOf(literal);if(at<0)return null;
        // Conditions may follow the selected sentence. Keep the whole available passage or a complete section.
        if(source.length()<=4000)return source.strip();
        int start=0,end=source.length();var headings=java.util.regex.Pattern.compile("(?m)^#{1,6}\\s+[^\\n]*").matcher(source);
        while(headings.find()){if(headings.start()<=at)start=headings.start();else if(headings.start()>=at+literal.length()){end=headings.start();break;}}
        String result=source.substring(start,end).strip();return result.length()<=4000?result:null;
    }
}
