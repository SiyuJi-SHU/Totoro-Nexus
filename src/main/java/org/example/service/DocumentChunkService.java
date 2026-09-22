package org.example.service;

import org.example.config.DocumentChunkConfig;
import org.example.dto.DocumentChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 文档分片服务
 * 负责将长文档切分为多个有语义完整性的小片段
 */
@Service
public class DocumentChunkService {

    private static final Logger logger = LoggerFactory.getLogger(DocumentChunkService.class);
    private static final int MIN_SECTION_CHARS = 200;
    private static final Pattern HEADING_LINE = Pattern.compile("^ {0,3}(#{1,6})[ \\t]+(.+?)[ \\t]*#*[ \\t]*$");

    @Autowired
    private DocumentChunkConfig chunkConfig;

    /**
     * 智能分片文档
     * 优先按照标题、段落边界进行分割，保持语义完整性
     * 
     * @param content 文档内容
     * @param filePath 文件路径（用于日志）
     * @return 文档分片列表
     */
    public List<DocumentChunk> chunkDocument(String content, String filePath) {
        List<DocumentChunk> chunks = new ArrayList<>();

        if (content == null || content.trim().isEmpty()) {
            logger.warn("文档内容为空: {}", filePath);
            return chunks;
        }

        // 1. 按 Markdown 层级构造带上下文的原文片段。
        List<Section> sections = splitByHeadings(content);
        
        // 2. 对每个章节进行进一步分片
        int globalChunkIndex = 0;
        for (Section section : sections) {
            List<DocumentChunk> sectionChunks = isHeadingStrategy()
                    ? chunkHeadingSection(section, globalChunkIndex)
                    : chunkSection(section, globalChunkIndex);
            chunks.addAll(sectionChunks);
            globalChunkIndex += sectionChunks.size();
        }

        logger.info("文档分片完成: {} -> {} 个分片", filePath, chunks.size());
        return chunks;
    }

    private boolean isHeadingStrategy() {
        return "heading".equalsIgnoreCase(chunkConfig.getStrategy());
    }

    private List<DocumentChunk> chunkHeadingSection(Section section, int chunkIndex) {
        DocumentChunk chunk = new DocumentChunk(
                section.content,
                section.startIndex,
                section.startIndex + section.content.length(),
                chunkIndex
        );
        chunk.setTitle(section.title);
        return List.of(chunk);
    }

    /** 按 Markdown 标题切分；纯标题并入后续正文，短相邻章节在不超上限时合并。 */
    private List<Section> splitByHeadings(String content) {
        List<Heading> headings = headings(content);
        if (headings.isEmpty()) return List.of(new Section(null, content, 0, content.length()));

        List<Section> sections = new ArrayList<>();
        if (!content.substring(0, headings.get(0).start).isBlank())
            sections.add(new Section(null, content.substring(0, headings.get(0).start), 0, headings.get(0).start));

        List<String> path = new ArrayList<>();
        List<AtomicSection> pendingHeadings = new ArrayList<>();
        for (int i = 0; i < headings.size(); i++) {
            Heading heading = headings.get(i);
            while (path.size() >= heading.level) path.remove(path.size() - 1);
            path.add(heading.title);
            int end = i + 1 < headings.size() ? headings.get(i + 1).start : content.length();
            var atomic = new AtomicSection(String.join(" > ", path), heading.start, heading.lineEnd, end);
            if (content.substring(heading.lineEnd, end).isBlank()) {
                pendingHeadings.add(atomic);
                continue;
            }
            int start = pendingHeadings.isEmpty() ? atomic.start : pendingHeadings.get(0).start;
            sections.add(new Section(atomic.title, content.substring(start, end), start, end));
            pendingHeadings.clear();
        }
        return mergeShortSections(sections);
    }

    private List<Heading> headings(String content) {
        List<Heading> result = new ArrayList<>();
        int offset = 0; String fence = null;
        for (String line : content.split("(?<=\\n)", -1)) {
            String logical = line.endsWith("\n") ? line.substring(0, line.length() - 1) : line;
            if (logical.endsWith("\r")) logical = logical.substring(0, logical.length() - 1);
            String stripped = logical.strip();
            if (stripped.startsWith("```") || stripped.startsWith("~~~")) {
                String marker = stripped.substring(0, 3);
                if (fence == null) fence = marker; else if (fence.equals(marker)) fence = null;
            } else if (fence == null) {
                var matcher = HEADING_LINE.matcher(logical);
                if (matcher.matches()) result.add(new Heading(matcher.group(1).length(), matcher.group(2).trim(), offset, offset + line.length()));
            }
            offset += line.length();
        }
        return result;
    }

    private List<Section> mergeShortSections(List<Section> sections) {
        int max = Math.max(32, chunkConfig.getMaxSize());
        List<Section> merged = new ArrayList<>();
        for (Section section : sections) {
            if (!merged.isEmpty()) {
                Section previous = merged.get(merged.size() - 1);
                boolean shortSection = meaningfulLength(section.content) < MIN_SECTION_CHARS || meaningfulLength(previous.content) < MIN_SECTION_CHARS;
                if (shortSection && previous.endIndex == section.startIndex && previous.content.length() + section.content.length() <= max) {
                    merged.set(merged.size() - 1, new Section(mergeTitles(previous.title, section.title),
                            previous.content + section.content, previous.startIndex, section.endIndex));
                    continue;
                }
            }
            merged.add(section);
        }
        return merged;
    }

    private int meaningfulLength(String content) {
        return Arrays.stream(content.split("\\R")).filter(line -> !HEADING_LINE.matcher(line).matches())
                .mapToInt(line -> line.strip().length()).sum();
    }

    private String mergeTitles(String first, String second) {
        if (first == null || first.isBlank()) return second;
        if (second == null || second.isBlank() || first.equals(second)) return first;
        int separator = second.lastIndexOf(" > ");
        String leaf = separator < 0 ? second : second.substring(separator + 3);
        return first.contains(" | " + leaf) || first.endsWith(" > " + leaf) ? first : first + " | " + leaf;
    }

    /**
     * 对单个章节进行分片
     */
    private List<DocumentChunk> chunkSection(Section section, int startChunkIndex) {
        List<DocumentChunk> chunks = new ArrayList<>();
        String content = section.content;
        String title = section.title;

        // 如果章节内容小于最大尺寸，直接作为一个分片
        if (content.length() <= chunkConfig.getMaxSize()) {
            DocumentChunk chunk = new DocumentChunk(
                content, 
                section.startIndex, 
                section.startIndex + content.length(), 
                startChunkIndex
            );
            chunk.setTitle(title);
            chunks.add(chunk);
            return chunks;
        }

        // Slice the original text: retain separators, bounded overlap and accurate offsets.
        int max = Math.max(32, chunkConfig.getMaxSize());
        int overlap = Math.max(0, Math.min(chunkConfig.getOverlap(), max / 2));
        int start = 0;
        while (start < content.length()) {
            int end = Math.min(content.length(), start + max);
            if (end < content.length()) {
                int paragraphEnd = content.lastIndexOf("\n\n", end - 1);
                if (paragraphEnd > start + max / 2) end = paragraphEnd + 2;
                else {
                    int lineEnd = content.lastIndexOf('\n', end - 1);
                    if (lineEnd > start + max / 2) end = lineEnd + 1;
                    else {
                        int wordEnd = lastNaturalBoundary(content, start + max / 2, end);
                        if (wordEnd > start) end = wordEnd;
                    }
                }
                if (end < content.length() && Character.isLowSurrogate(content.charAt(end))) end--;
            }
            int storedEnd = end;
            if (end == content.length()) while (storedEnd > start && Character.isWhitespace(content.charAt(storedEnd - 1))) storedEnd--;
            DocumentChunk chunk = new DocumentChunk(content.substring(start, storedEnd),
                    section.startIndex + start, section.startIndex + storedEnd, startChunkIndex + chunks.size());
            chunk.setTitle(title);
            chunks.add(chunk);
            if (end == content.length()) break;
            start = naturalOverlapStart(content, Math.max(start + 1, end - overlap), end);
            if (Character.isLowSurrogate(content.charAt(start))) start++;
        }
        return chunks;
    }

    private int lastNaturalBoundary(String content, int lower, int upper) {
        for (int i = upper; i > lower; i--) {
            char previous = content.charAt(i - 1);
            if (Character.isWhitespace(previous) || ".!?。！？；;，,".indexOf(previous) >= 0) return i;
        }
        return upper;
    }

    private int naturalOverlapStart(String content, int target, int end) {
        for (int i = target; i < end; i++) {
            if (i == 0 || Character.isWhitespace(content.charAt(i - 1))) {
                while (i < end && Character.isWhitespace(content.charAt(i))) i++;
                if (i < end) return i;
            }
        }
        return target;
    }

    /**
     * 章节数据类
     */
    private static class Section {
        String title;
        String content;
        int startIndex;
        int endIndex;

        Section(String title, String content, int startIndex, int endIndex) {
            this.title = title;
            this.content = content;
            this.startIndex = startIndex;
            this.endIndex = endIndex;
        }
    }

    private record Heading(int level, String title, int start, int lineEnd) {}
    private record AtomicSection(String title, int start, int lineEnd, int end) {}
}
