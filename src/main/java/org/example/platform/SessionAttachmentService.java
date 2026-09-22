package org.example.platform;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Service
public class SessionAttachmentService {
    private final JdbcClient db;
    private final AgentRunStore runStore;
    private final Path uploadRoot;
    private static final int MAX_ATTACHMENTS_PER_SESSION = 10;
    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024; // 5MB

    @org.springframework.beans.factory.annotation.Autowired
    public SessionAttachmentService(JdbcClient db, AgentRunStore runStore,
            @org.springframework.beans.factory.annotation.Value("${file.upload.path:./uploads}") String root) {
        this(db, runStore, Paths.get(root).resolve(".platform/session-attachments"));
    }

    SessionAttachmentService(JdbcClient db, AgentRunStore runStore, Path root) {
        this.db = db;
        this.runStore = runStore;
        this.uploadRoot = root.toAbsolutePath().normalize();
        try {
            Files.createDirectories(uploadRoot);
        } catch (IOException e) {
            throw new RuntimeException("无法创建附件存储目录", e);
        }
    }

    private Path checkedPath(String stored) {
        Path target=Paths.get(stored).toAbsolutePath().normalize();
        Path legacy=uploadRoot.getParent()!=null&&uploadRoot.getParent().getFileName().toString().equals(".platform")?uploadRoot.getParent().getParent().resolve("sessions"):uploadRoot;
        if(!target.startsWith(uploadRoot)&&!target.startsWith(legacy))throw PlatformCatalog.bad("附件路径超出存储范围");
        for(Path cursor=target;cursor!=null;cursor=cursor.getParent())if(Files.isSymbolicLink(cursor))throw PlatformCatalog.bad("附件路径不能包含符号链接");
        return target;
    }

    /** 列出会话的所有附件（需验证所有者） */
    public List<SessionAttachment> list(String sessionId, String ownerId) {
        // 验证会话所有者
        runStore.session(sessionId, ownerId);

        return db.sql("""
            SELECT id, session_id, filename, content_type, file_size, uploaded_at, uploaded_by
            FROM session_attachments
            WHERE session_id = ?
            ORDER BY uploaded_at
        """).param(sessionId).query((rs, rowNum) -> new SessionAttachment(
            rs.getString("id"),
            rs.getString("session_id"),
            rs.getString("filename"),
            rs.getString("content_type"),
            rs.getInt("file_size"),
            null, // 不暴露storagePath
            rs.getTimestamp("uploaded_at").toInstant(),
            rs.getString("uploaded_by")
        )).list();
    }

    /** 上传附件 */
    @Transactional
    public SessionAttachment upload(String sessionId, MultipartFile file, String userId) throws IOException {
        // 验证会话所有者
        runStore.writableSession(sessionId, userId);
        db.sql("SELECT id FROM agent_sessions WHERE id=? FOR UPDATE").param(sessionId).query(String.class).single();

        // 验证文件
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件为空");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件大小超过5MB限制");
        }
        String filename = file.getOriginalFilename();
        if (filename != null && (filename.length() > 255 || filename.contains("/") || filename.contains("\\")
                || filename.contains(":") || filename.chars().anyMatch(Character::isISOControl))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件名无效");
        }
        if (!SessionAttachment.isAllowedExtension(filename)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "只支持 .txt, .log, .md, .json 文件");
        }

        // 检查附件数量限制
        int count = db.sql("SELECT COUNT(*) FROM session_attachments WHERE session_id = ?")
            .param(sessionId).query(Integer.class).single();
        if (count >= MAX_ATTACHMENTS_PER_SESSION) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "每个会话最多" + MAX_ATTACHMENTS_PER_SESSION + "个附件");
        }

        // 生成存储路径
        String id = UUID.randomUUID().toString();
        String ext = filename.substring(filename.lastIndexOf('.'));
        if (!sessionId.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("会话标识无效");
        Path sessionDir = uploadRoot.resolve(sessionId);
        if (Files.isSymbolicLink(uploadRoot) || Files.isSymbolicLink(sessionDir)) throw new IllegalArgumentException("附件路径无效");
        Files.createDirectories(sessionDir);
        String storageFilename = UUID.randomUUID().toString() + ext;
        Path storagePath = checkedPath(sessionDir.resolve(storageFilename).toString());

        // 安全检查：确保路径在uploadRoot内
        if (!storagePath.normalize().startsWith(uploadRoot)) {
            throw new IllegalArgumentException("非法存储路径");
        }

        // 保存文件
        try {
            file.transferTo(storagePath);
            if (Files.size(storagePath) > MAX_FILE_SIZE) throw new IOException("文件超过限制");
            Files.readString(storagePath, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            Files.deleteIfExists(storagePath);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件必须是有效 UTF-8 文本，且不超过5MB");
        }

        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCompletion(int status) {
                        if (status != STATUS_COMMITTED) try { Files.deleteIfExists(storagePath); } catch (IOException ignored) {}
                    }
                });
        }

        // 保存元数据
        String contentType = SessionAttachment.inferContentType(filename);
        Instant now = Instant.now();
        try {
            db.sql("""
                INSERT INTO session_attachments
                (id, session_id, filename, content_type, file_size, storage_path, uploaded_at, uploaded_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """).params(id, sessionId, filename, contentType, (int)file.getSize(),
                        storagePath.toString(), java.sql.Timestamp.from(now), userId).update();
        } catch (Exception e) {
            // 数据库失败时清理文件
            try {
                Files.deleteIfExists(storagePath);
            } catch (IOException ignored) {}
            throw e;
        }

        // 更新context_revision
        db.sql("UPDATE agent_sessions SET context_revision = context_revision + 1 WHERE id = ?")
            .param(sessionId).update();

        return new SessionAttachment(id, sessionId, filename, contentType,
                                    (int)file.getSize(), null, now, userId);
    }

    /** 读取附件内容（文本）（需验证所有者） */
    public String readContent(String attachmentId, String ownerId) throws IOException {
        var attachment = getAttachmentWithOwnerCheck(attachmentId, ownerId);

        Path path = checkedPath(attachment.storagePath());
        if (!Files.exists(path)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "附件文件丢失");
        }

        // 限制读取大小，防止OOM
        long size = Files.size(path);
        if (size > MAX_FILE_SIZE) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "附件超过5MB限制");
        return Files.readString(path, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 获取附件文件路径（用于下载）（需验证所有者） */
    public Path getFilePath(String attachmentId, String ownerId) {
        var attachment = getAttachmentWithOwnerCheck(attachmentId, ownerId);
        return checkedPath(attachment.storagePath());
    }

    /** 删除附件（需验证所有者和会话匹配） */
    @Transactional
    public void delete(String sessionId, String attachmentId, String ownerId) {
        // 验证会话所有者与配置版本
        runStore.writableSession(sessionId, ownerId);
        db.sql("SELECT id FROM agent_sessions WHERE id=? FOR UPDATE").param(sessionId).query(String.class).single();

        // 获取附件并验证属于该会话
        String storagePath = db.sql("""
            SELECT storage_path FROM session_attachments
            WHERE id = ? AND session_id = ?
        """).params(attachmentId, sessionId).query(String.class).optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "附件不存在"));

        db.sql("DELETE FROM session_attachments WHERE id = ?").param(attachmentId).update();
        db.sql("UPDATE agent_sessions SET context_revision=context_revision+1 WHERE id=?").param(sessionId).update();
        Runnable remove = () -> { try { Files.deleteIfExists(checkedPath(storagePath)); } catch (IOException ignored) {} };
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCommit() { remove.run(); }
                });
        } else remove.run();
    }

    void deleteStoredFilesAfterCommit(Collection<String> storedPaths) {
        List<Path> targets=storedPaths.stream().map(this::checkedPath).toList();
        Runnable remove=()->targets.forEach(target->{
            try {
                Files.deleteIfExists(target);
                Path parent=target.getParent();
                if(parent!=null&&parent.startsWith(uploadRoot))Files.deleteIfExists(parent);
            } catch (IOException ignored) {}
        });
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCommit() { remove.run(); }
                });
        } else remove.run();
    }

    /** 内部方法：获取附件并验证所有者 */
    private SessionAttachment getAttachmentWithOwnerCheck(String attachmentId, String ownerId) {
        var attachment = db.sql("""
            SELECT a.id, a.session_id, a.filename, a.content_type, a.file_size,
                   a.storage_path, a.uploaded_at, a.uploaded_by
            FROM session_attachments a
            JOIN agent_sessions s ON a.session_id = s.id
            WHERE a.id = ? AND s.owner_id = ?
        """).params(attachmentId, ownerId).query((rs, rowNum) -> new SessionAttachment(
            rs.getString("id"),
            rs.getString("session_id"),
            rs.getString("filename"),
            rs.getString("content_type"),
            rs.getInt("file_size"),
            rs.getString("storage_path"),
            rs.getTimestamp("uploaded_at").toInstant(),
            rs.getString("uploaded_by")
        )).optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "附件不存在或无权访问"));

        return attachment;
    }
}
