package org.example.platform;

import java.util.regex.Pattern;

/** A rewrite may improve wording but must not silently remove exact identifiers or negative conditions. */
final class QueryRewriteGuard {
    private static final Pattern ANCHOR=Pattern.compile("[A-Za-z0-9_./:-]*[A-Z][A-Z0-9_]{2,}[A-Za-z0-9_./:-]*|[A-Za-z0-9_./:-]*\\d[A-Za-z0-9_./:-]*|`[^`]+`");
    private static final Pattern NEGATION=Pattern.compile("(?i)(?:\\b(?:not|no|without|never|exclude|excluding)\\b|未|不|没有|无|排除)");
    static String preserve(String original,String rewritten) {
        if(rewritten==null||rewritten.isBlank()||rewritten.length()>8000)return original;
        var anchors=ANCHOR.matcher(original);
        while(anchors.find())if(!rewritten.contains(anchors.group()))return original;
        for(String clause:original.split("[。；;!?！？\\n]"))
            if(NEGATION.matcher(clause).find()&&!rewritten.contains(clause.strip()))return original;
        return rewritten.strip();
    }
}
