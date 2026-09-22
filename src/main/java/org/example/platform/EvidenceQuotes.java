package org.example.platform;

import java.util.*;

/** Tolerate presentation-only whitespace/code/bold markers, then return the exact original source slice. */
final class EvidenceQuotes {
    private EvidenceQuotes(){}
    static Optional<String> resolve(String source,String quote) {
        if(source==null||quote==null||quote.isBlank())return Optional.empty();
        if(source.contains(quote))return Optional.of(quote);
        var s=compact(source);var q=compact(quote);if(q.text().isBlank())return Optional.empty();
        int at=s.text().indexOf(q.text());
        if(at<0)return Optional.empty();
        return Optional.of(source.substring(s.positions().get(at),s.positions().get(at+q.text().length()-1)+1));
    }
    private record Compact(String text,List<Integer> positions){}
    private static Compact compact(String value) {
        StringBuilder text=new StringBuilder();List<Integer> positions=new ArrayList<>();
        for(int i=0;i<value.length();i++) {
            char c=value.charAt(i);
            if(Character.isWhitespace(c)||c=='`')continue;
            if(c=='*'&&i+1<value.length()&&value.charAt(i+1)=='*'){i++;continue;}
            text.append(c);positions.add(i);
        }
        return new Compact(text.toString(),positions);
    }
}
