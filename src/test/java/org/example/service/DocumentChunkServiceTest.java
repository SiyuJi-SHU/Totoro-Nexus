package org.example.service;

import org.example.config.DocumentChunkConfig;
import org.example.dto.DocumentChunk;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentChunkServiceTest {

    private DocumentChunkService service(int maxSize, int overlap, String strategy) {
        DocumentChunkConfig config = new DocumentChunkConfig();
        config.setMaxSize(maxSize);
        config.setOverlap(overlap);
        config.setStrategy(strategy);
        DocumentChunkService service = new DocumentChunkService();
        ReflectionTestUtils.setField(service, "chunkConfig", config);
        return service;
    }

    @Test
    void headingStrategyKeepsEachMarkdownSectionAsOneChunk() {
        DocumentChunkService service = service(80, 10, "heading");

        String firstParagraph = "第一段用于描述告警现象。".repeat(8);
        String secondParagraph = "第二段用于描述排查步骤。".repeat(8);
        String content = "# CPU 告警\n\n" + firstParagraph + "\n\n" + secondParagraph
                + "\n\n# 验证\n\n确认指标恢复。";

        List<DocumentChunk> chunks = service.chunkDocument(content, "cpu.md");

        assertEquals(2, chunks.size());
        assertEquals("CPU 告警", chunks.get(0).getTitle());
        assertTrue(chunks.get(0).getContent().contains(firstParagraph));
        assertTrue(chunks.get(0).getContent().contains(secondParagraph));
        assertEquals("验证", chunks.get(1).getTitle());
    }

    @Test
    void titleOnlyHierarchyIsAttachedToTheFollowingBody() {
        String content = "# ApdexSLOViolation\n\n## Overview\n\n### General Troubleshooting Steps\n\n"
                + "Inspect the slow request logs and recent deployments.\n";

        List<DocumentChunk> chunks = service(1200, 200, "size").chunkDocument(content, "apdex.md");

        assertEquals(1, chunks.size());
        assertEquals("ApdexSLOViolation > Overview > General Troubleshooting Steps", chunks.get(0).getTitle());
        assertEquals(content, chunks.get(0).getContent());
        assertFalse(chunks.stream().anyMatch(chunk -> chunk.getContent().strip().matches("^#{1,6}\\s+[^\\r\\n]+$")));
    }

    @Test
    void shortCoherentMarkdownRemainsOneChunk() {
        String content = "# Handbook\n\n## Access\n\nRequest access from the service owner.\n";

        List<DocumentChunk> chunks = service(1200, 200, "size").chunkDocument(content, "handbook.md");

        assertEquals(1, chunks.size());
        assertEquals(content, chunks.get(0).getContent());
    }

    @Test
    void longChunksKeepExactOffsetsAndOverlapAtWordBoundaries() {
        String content = "# Incident\n\n" + "alpha beta gamma delta epsilon zeta eta theta. ".repeat(80);

        List<DocumentChunk> chunks = service(180, 45, "size").chunkDocument(content, "incident.md");

        assertTrue(chunks.size() > 2);
        for (DocumentChunk chunk : chunks) {
            assertEquals(chunk.getContent(), content.substring(chunk.getStartIndex(), chunk.getEndIndex()));
            assertTrue(chunk.getContent().length() <= 180);
            if (chunk.getStartIndex() > 0)
                assertTrue(Character.isWhitespace(content.charAt(chunk.getStartIndex() - 1)));
        }
    }
}
