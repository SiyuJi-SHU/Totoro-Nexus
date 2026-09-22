package org.example.service;

import org.springframework.stereotype.Service;
import java.util.*;
import java.util.regex.Pattern;

/** Pure source reading. Callers resolve and authorize the immutable source before passing it here. */
@Service
public class DocumentReadingService {
    public static final int MAX_WINDOW_CHARS = 20_000;
    private static final Pattern HEADING = Pattern.compile("^ {0,3}(#{1,6})\\s+(.+?)\\s*#*\\s*$");
    public record Source(String documentId, String version, String name, String content) {
        public Source {
            Objects.requireNonNull(documentId); Objects.requireNonNull(version);
            Objects.requireNonNull(name); Objects.requireNonNull(content);
        }
    }
    public record Section(String id, String title, int level, int start, int end) {}
    public record Window(String documentId, String version, String sourceFile, int start, int end,
                         int totalChars, String content, boolean truncated, Integer nextOffset) {}

    public List<Section> sections(Source source) {
        record Heading(String title, int level, int offset) {}
        List<Heading> headings = new ArrayList<>();
        int offset = 0; String fence = null;
        for (String line : source.content().split("(?<=\\n)", -1)) {
            String stripped = line.strip();
            if (stripped.startsWith("```") || stripped.startsWith("~~~")) {
                String marker = stripped.substring(0, 3);
                if (fence == null) fence = marker;
                else if (fence.equals(marker)) fence = null;
            } else if (fence == null) {
                var match = HEADING.matcher(line.stripTrailing());
                if (match.matches()) headings.add(new Heading(match.group(2), match.group(1).length(), offset));
            }
            offset += line.length();
        }
        List<Section> result = new ArrayList<>();
        if (headings.isEmpty()) return List.of(new Section("s0", "正文", 0, 0, source.content().length()));
        if (headings.get(0).offset() > 0) result.add(new Section("intro", "前言", 0, 0, headings.get(0).offset()));
        for (int i = 0; i < headings.size(); i++) {
            var heading = headings.get(i); int end = source.content().length();
            for (int j = i + 1; j < headings.size(); j++) {
                if (headings.get(j).level() <= heading.level()) { end = headings.get(j).offset(); break; }
            }
            result.add(new Section("s" + i, heading.title(), heading.level(), heading.offset(), end));
        }
        return List.copyOf(result);
    }

    public Window read(Source source, String expectedVersion, String sectionId, Integer offset, Integer maxChars) {
        if (!source.version().equals(expectedVersion)) throw new IllegalArgumentException("文档版本不匹配，请重新定位来源");
        int lower = 0, upper = source.content().length();
        if (sectionId != null && !sectionId.isBlank()) {
            Section section = sections(source).stream().filter(s -> s.id().equals(sectionId)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("章节不存在"));
            lower = section.start(); upper = section.end();
        }
        int start = offset == null ? lower : offset;
        if (start < lower || start > upper) throw new IllegalArgumentException("读取位置超出范围");
        if (start > 0 && start < source.content().length() && Character.isLowSurrogate(source.content().charAt(start))
                && Character.isHighSurrogate(source.content().charAt(start - 1))) throw new IllegalArgumentException("读取位置不能截断字符");
        int requested = maxChars == null ? 8_000 : maxChars;
        if (requested < 1 || requested > MAX_WINDOW_CHARS) throw new IllegalArgumentException("单次读取长度必须在1至20000字符之间");
        int end = (int) Math.min(upper, (long) start + requested);
        if (end < upper && end > start && Character.isHighSurrogate(source.content().charAt(end - 1))
                && Character.isLowSurrogate(source.content().charAt(end))) end--;
        if (end == start && end < upper) throw new IllegalArgumentException("读取长度不足以返回完整字符");
        boolean truncated = end < upper;
        return new Window(source.documentId(), source.version(), source.name(), start, end, source.content().length(),
                source.content().substring(start, end), truncated, truncated ? end : null);
    }
}
