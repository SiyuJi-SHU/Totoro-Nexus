package org.example.platform;

import java.time.Instant;

/** 会话附件元数据（storagePath仅内部使用，不暴露给API） */
public record SessionAttachment(
    String id,
    String sessionId,
    String filename,
    String contentType,
    int fileSize,
    String storagePath,  // nullable, 仅内部使用
    Instant uploadedAt,
    String uploadedBy
) {
    /** 检查文件扩展名是否允许 */
    public static boolean isAllowedExtension(String filename) {
        if (filename == null || filename.isBlank()) return false;
        String lower = filename.toLowerCase();
        return lower.endsWith(".txt") || lower.endsWith(".log") ||
               lower.endsWith(".md") || lower.endsWith(".json");
    }

    /** 从文件名推断MIME类型 */
    public static String inferContentType(String filename) {
        if (filename == null) return "application/octet-stream";
        String lower = filename.toLowerCase();
        if (lower.endsWith(".txt")) return "text/plain";
        if (lower.endsWith(".log")) return "text/plain";
        if (lower.endsWith(".md")) return "text/markdown";
        if (lower.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }
}
