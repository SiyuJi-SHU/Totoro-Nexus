package org.example.platform;

import org.example.dto.*;
import java.util.*;
import java.util.regex.Pattern;

/** Literal deployment identifiers in a runbook are not automatically the target of the current incident. */
final class IncidentActionTargets {
    private static final Pattern HOST=Pattern.compile("(?i)\\b(?:https?://)?((?:[a-z0-9-]+\\.)+(?:com|net|org|internal|local|example|test|io|dev|cloud|svc)|(?:[0-9]{1,3}\\.){3}[0-9]{1,3})(?![a-z0-9_.-])(?::\\d+)?");
    private static final Pattern PROJECT=Pattern.compile("\\b([a-z][a-z0-9_-]+)\\s*项目");
    static GroundedAnalysis.Action bind(GroundedAnalysis.Action action,IncidentSnapshot incident) {
        String command=Objects.toString(action.command(),""),pre=Objects.toString(action.prerequisites(),"");
        String facts=String.join("\n",incident.observations().values());Set<String> unknown=new LinkedHashSet<>();
        for(var source:List.of(command,pre)) {
            var hosts=HOST.matcher(source);while(hosts.find()) {
                if(source.equals(pre)&&hosts.group().matches("(?i)^https?://.*"))continue; // A navigation link is not itself a deployment hostname.
                String host=hosts.group(1);String suffix=host.substring(host.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);
                if(!Set.of("md","txt","json","yml","yaml","xml","sql","log").contains(suffix)&&!facts.contains(host))unknown.add(host);
            }
            var projects=PROJECT.matcher(source);while(projects.find())if(!facts.contains(projects.group(1)))unknown.add(projects.group(1));
        }
        if(unknown.isEmpty())return action;
        String notice="文档中的环境标识（"+String.join("、",unknown)+"）尚未与本次现场对应，需先确认目标环境。";
        // Preserve the cited original for review, but do not present an unbound host as a ready command.
        return new GroundedAnalysis.Action(action.text(),"",notice+(pre.isBlank()?"":" 原文适用条件："+pre),action.citations());
    }
}
