package org.example.platform;

import java.util.regex.Pattern;

/** Check copied numeric values and alphanumeric identifiers; semantic entailment still requires review. */
final class EvidenceAssertions {
    private static final Pattern TOKENS=Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:\\d{2})|[A-Za-z][A-Za-z_-]*\\d[A-Za-z0-9_.-]*|(?<![A-Za-z0-9.])\\d+(?:\\.\\d+)*%?(?![A-Za-z])");
    static boolean supported(String statement,String sources) {
        if(statement==null)return false;
        if(sources!=null&&sources.contains(statement))return true;
        var matches=TOKENS.matcher(statement);
        while(matches.find()) {
            String value=matches.group();
            if(value.matches("\\d{1,2}")&&listMarker(statement,matches.start(),matches.end()))continue;
            if(value.matches("(?:19|20)\\d{2}")&&Pattern.compile("(?<![A-Za-z0-9])"+Pattern.quote(value)+"(?!\\d)").matcher(sources).find())continue;
            if(durationEquivalent(statement,sources,matches.end(),value))continue;
            if(!Pattern.compile("(?i)(?<![A-Za-z0-9.])"+Pattern.quote(value)+"(?![A-Za-z0-9]|\\.[A-Za-z0-9])").matcher(sources).find())return false;
        }return true;
    }
    private static boolean durationEquivalent(String statement,String sources,int end,String value) {
        if(!value.matches("\\d+(?:\\.\\d+)?")||sources==null)return false;
        String tail=statement.substring(end,Math.min(statement.length(),end+16)).stripLeading().toLowerCase();
        String units=tail.matches("^(?:秒|s(?:ec(?:ond)?s?)?)(?:\\b|[^a-z]).*")?"s|sec|secs|second|seconds|秒":
            tail.matches("^(?:分钟|分|min(?:ute)?s?|m)(?:\\b|[^a-z]).*")?"m|min|mins|minute|minutes|分钟|分":
            tail.matches("^(?:小时|时|h(?:r|our)?s?)(?:\\b|[^a-z]).*")?"h|hr|hrs|hour|hours|小时|时":
            tail.matches("^(?:天|日|d(?:ay)?s?)(?:\\b|[^a-z]).*")?"d|day|days|天|日":
            tail.matches("^(?:周|星期|w(?:eek)?s?)(?:\\b|[^a-z]).*")?"w|week|weeks|周|星期":"";
        return !units.isEmpty()&&Pattern.compile("(?i)(?<![A-Za-z0-9.])"+Pattern.quote(value)+"\\s*(?:"+units+")(?![A-Za-z])").matcher(sources).find();
    }
    private static boolean listMarker(String text,int start,int end) {
        if(end>=text.length()||")）.、".indexOf(text.charAt(end))<0)return false;
        if(start==0)return true;
        char before=text.charAt(start-1);
        if((before=='('||before=='（')&&(text.charAt(end)==')'||text.charAt(end)=='）')) {
            int opener=start-1;
            return opener==0||Character.isWhitespace(text.charAt(opener-1))||"：:；;,，".indexOf(text.charAt(opener-1))>=0;
        }
        return Character.isWhitespace(before)||"：:；;,，".indexOf(before)>=0;
    }
}
