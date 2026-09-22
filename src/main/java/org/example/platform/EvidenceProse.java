package org.example.platform;

import java.util.regex.Pattern;

/** Free prose fields cannot bypass the separately sourced command field. */
public final class EvidenceProse {
    /** Headings alone identify a topic; they cannot support a procedure. */
    public static boolean hasBody(String quote) {
        return quote!=null&&quote.lines().anyMatch(line->!line.isBlank()&&!line.strip().matches("#{1,6}\\s+.*|[-=*]{3,}"));
    }
    private EvidenceProse(){}
    /** Prefer literal code spans/blocks; also recognize explicit SQL in plain-text manuals. */
    public static boolean commandSource(String source,String command) {
        if(source==null||command==null||command.isBlank())return false;
        String normalized=source.replace("\r\n","\n"),needle=command.replace("\r\n","\n").strip();
        var blocks=Pattern.compile("(?ms)^\\s*```[^\\n]*\\n(.*?)^\\s*```|`([^`\\n]+)`").matcher(normalized);
        while(blocks.find()){String code=blocks.group(1)==null?blocks.group(2):blocks.group(1);if(code.strip().contains(needle))return true;}
        var indented=Pattern.compile("(?m)(?:^(?: {4}|\\t).*(?:\\n|$))+").matcher(normalized);
        while(indented.find()){
            String code=indented.group().replaceAll("(?m)^(?: {4}|\\t)","").strip();
            if(code.contains(needle))return true;
        }
        return normalized.contains(needle)&&needle.matches("(?is)^(?:SELECT|SHOW|EXPLAIN|WITH)\\s+[^`]+;?$");
    }
    private static final Pattern EXECUTABLE=Pattern.compile("(?i)```|`|\\b(?:sudo|curl|wget|ssh|telnet|redis-cli|openssl|kubectl|glsh|psql|systemctl)\\s+|\\s-[A-Za-z]{1,2}\\b|\\|\\s*[A-Za-z]");
    public static String missing(String value) {
        if(value==null)return null;String text=value.strip().replaceAll("[（(](?:如|例如|e\\.g\\.).*?[）)]","").strip();
        if(EXECUTABLE.matcher(text).find()) {
            // Keep the information request before an illustrative invocation, never the unverified invocation itself.
            int paren=text.indexOf('（');if(paren<0)paren=text.indexOf('(');
            if(paren>0&&!EXECUTABLE.matcher(text.substring(0,paren)).find())text=text.substring(0,paren).strip();
            else return null;
        }
        return text.isBlank()||text.length()>1000?null:text;
    }
    public static boolean embedsCommand(String value){return value!=null&&EXECUTABLE.matcher(value).find();}
    public static boolean commandsSupported(String value,String sources) {
        if(value==null)return true;
        var code=Pattern.compile("`([^`]+)`").matcher(value);
        while(code.find())if(EXECUTABLE.matcher(code.group(1)).find()&&!sources.contains(code.group(1).strip()))return false;
        String prose=value.replaceAll("`[^`]+`","");
        var commands=Pattern.compile("(?i)\\b(?:sudo|curl|wget|ssh|telnet|redis-cli|openssl|kubectl|glsh|psql|systemctl)\\s+[^\\p{IsHan}。；;（）\\n]+").matcher(prose);
        while(commands.find())if(!sources.contains(commands.group().strip()))return false;
        return true;
    }
}
