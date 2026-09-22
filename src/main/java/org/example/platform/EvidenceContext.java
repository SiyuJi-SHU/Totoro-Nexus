package org.example.platform;

import java.util.*;

/** Budget immutable source ranges by their union, not by the number of retrieval/read calls. */
final class EvidenceContext {
    private EvidenceContext() {}
    static int size(Collection<AgentToolRegistry.Evidence> evidence) {
        Map<String,List<int[]>> ranges=new HashMap<>();int other=0;
        for(var e:evidence) {
            if(!e.kind().equals("document")||e.end()-e.start()!=e.content().length()){other+=e.content().length();continue;}
            ranges.computeIfAbsent(e.documentId()+":"+e.version(),k->new ArrayList<>()).add(new int[]{e.start(),e.end()});
        }
        for(var list:ranges.values()) {
            list.sort(Comparator.comparingInt(r->r[0]));int start=-1,end=-1;
            for(var r:list){if(start<0){start=r[0];end=r[1];}else if(r[0]<=end)end=Math.max(end,r[1]);else{other+=end-start;start=r[0];end=r[1];}}
            if(start>=0)other+=end-start;
        }
        return other;
    }
    static boolean add(AgentToolRegistry.Context context,AgentToolRegistry.Evidence evidence,int budget) {
        if(context.evidence.containsKey(evidence.id()))return true;
        var all=new ArrayList<>(context.evidence.values());all.add(evidence);int size=size(all);
        if(size>budget)return false;
        context.evidence.put(evidence.id(),evidence);context.evidenceChars=size;return true;
    }
}
