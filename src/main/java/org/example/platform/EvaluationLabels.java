package org.example.platform;

import java.util.*;

/** A label quotes actual answer-bearing spans; sampling never depends on index insertion order. */
final class EvaluationLabels {
    private EvaluationLabels() {}
    static List<PlatformChunk> sample(List<PlatformChunk> chunks,Set<String> excluded,int count) {
        Map<String,List<PlatformChunk>> groups=new TreeMap<>();Map<String,Integer> used=new HashMap<>();
        for(var c:chunks) {
            if(excluded.contains(c.id()))used.merge(c.documentId(),1,Integer::sum);
            else if(c.content()!=null&&c.content().strip().length()>=80)groups.computeIfAbsent(c.documentId(),k->new ArrayList<>()).add(c);
        }
        // A stable hash distributes positions throughout each document instead of favoring its prefix.
        groups.values().forEach(g->g.sort(Comparator.comparing(c->org.example.service.KnowledgeFiles.digest(c.id()))));
        List<PlatformChunk> selected=new ArrayList<>();
        while(selected.size()<count&&!groups.isEmpty()) {
            String key=groups.keySet().stream().min(Comparator.comparingInt((String k)->used.getOrDefault(k,0)).thenComparing(k->k)).orElseThrow();
            var group=groups.get(key);selected.add(group.remove(0));used.merge(key,1,Integer::sum);if(group.isEmpty())groups.remove(key);
        }
        return List.copyOf(selected);
    }
    static List<String> resolve(String content,List<String> quotes) {
        if(quotes==null||quotes.isEmpty()||quotes.size()>5)return List.of();
        List<String> result=new ArrayList<>();
        for(String quote:quotes) {
            if(quote==null||quote.strip().length()<12||quote.length()>2000)return List.of();
            var exact=EvidenceQuotes.resolve(content,quote);if(exact.isEmpty())return List.of();result.add(exact.get());
        }
        return result.stream().distinct().toList();
    }
    static boolean covers(List<String> quotes,List<String> contents) {
        return quotes.stream().allMatch(q->contents.stream().anyMatch(c->EvidenceQuotes.resolve(c,q).isPresent()));
    }
}
