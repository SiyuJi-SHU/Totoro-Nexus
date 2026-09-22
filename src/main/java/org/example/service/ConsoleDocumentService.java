package org.example.service;

import com.google.gson.Gson;
import org.example.config.FileUploadConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.nio.file.*;
import java.nio.charset.*;
import java.nio.ByteBuffer;
import java.io.IOException;
import java.util.*;

/** Both upload endpoints use these same validation, deduplication and commit rules. */
@Service
public class ConsoleDocumentService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ConsoleDocumentService.class);
    private final KnowledgeFiles files;
    private final ConsoleDocumentIndexService index;
    private final VectorIndexService writer;
    @Autowired
    public ConsoleDocumentService(KnowledgeFiles files, ConsoleDocumentIndexService index, VectorIndexService writer) {
        this.files = files; this.index = index; this.writer = writer;
    }
    public ConsoleDocumentService(FileUploadConfig config, ConsoleDocumentIndexService index, VectorIndexService writer) {
        this(new KnowledgeFiles(config), index, writer);
    }
    Path resolve(String name) throws IOException { return files.resolve(name); }
    public static List<String> matchingSources(String name, Set<String> sources) {
        String normalizedName = name.replace('\\','/');
        return sources.stream().filter(Objects::nonNull).filter(s -> {
            String normalized = s.replace('\\','/');
            String basename = normalized.substring(normalized.lastIndexOf('/') + 1);
            return normalized.equals(normalizedName) || normalized.endsWith("/" + normalizedName)
                    || (!normalizedName.contains("/") && basename.equals(normalizedName));
        }).toList();
    }
    private List<String> sourcesFor(String name, Set<String> sources) { return matchingSources(name, sources); }

    public List<DocumentInfo> list() throws IOException {
        files.lock().readLock().lock();
        try {
            Map<String,ConsoleDocumentIndexService.SourceStats> stats;
            try { stats = files.indexHealthy() ? index.statistics() : null; }
            catch (RuntimeException e) { log.warn("Document index statistics unavailable: {}", e.getClass().getSimpleName()); stats = null; }
            List<DocumentInfo> result = new ArrayList<>();
            for (Path p : files.paths()) {
                String name = files.name(p);
                List<String> sources = stats == null ? List.of() : sourcesFor(name, stats.keySet());
                Long count = stats == null ? null : 0L; long expected = 0;
                if (stats != null) for (String source : sources) { count += stats.get(source).count(); expected += stats.get(source).expected(); }
                String status = stats == null ? "INDEX_UNAVAILABLE" : count == 0 ? "UNINDEXED"
                        : sources.size() > 1 || expected > 0 && count != expected ? "INDEX_FAILED" : "INDEXED";
                result.add(new DocumentInfo(name, Files.size(p), Files.getLastModifiedTime(p).toMillis(), count, status, p.getFileName().toString(), stats != null && sources.size() <= 1));
            }
            return result;
        } finally { files.lock().readLock().unlock(); }
    }
    public Path existing(String name) throws IOException {
        Path path = resolve(name);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在");
        return path;
    }
    public Map<String,Object> preview(String name) throws IOException {
        files.lock().readLock().lock();
        try {
            String content = Files.readString(existing(name));
            return Map.of("name", name, "content", content.substring(0, Math.min(100000, content.length())), "truncated", content.length() > 100000);
        } finally { files.lock().readLock().unlock(); }
    }
    public synchronized DocumentInfo upload(MultipartFile file) throws Exception {
        String name = file.getOriginalFilename();
        if (name == null || name.contains("/") || name.contains("\\") || name.contains(":")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件名不合法");
        Path target = resolve(name);
        if (file.isEmpty() || file.getSize() > KnowledgeFiles.MAX_BYTES) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件需非空且不超过5MB");
        String content;
        try { content = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(file.getBytes())).toString(); }
        catch (CharacterCodingException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请上传UTF-8编码的Markdown或TXT文档"); }
        if (content.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文档正文不能为空");
        boolean existed = Files.exists(target);
        boolean same = existed && Arrays.equals(Files.readAllBytes(target), file.getBytes());
        Map<String,ConsoleDocumentIndexService.SourceStats> stats;
        try { writer.recoverPending(); stats = index.statistics(); }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "向量索引当前不可用，文档未更新", e); }
        List<String> sources = sourcesFor(name, stats.keySet());
        if (same && sources.size() == 1) {
            var state = stats.get(sources.get(0));
            var rows = index.snapshot(sources.get(0));
            boolean complete = state.count() > 0 && state.expected() == state.count() && rows.size() == state.count()
                    && rows.stream().allMatch(row -> KnowledgeFiles.originalExcerpt(content, Objects.toString(row.get("content"), "")).isPresent());
            if (complete) return withAction(list().stream().filter(d -> d.name().equals(name)).findFirst().orElseThrow(), "unchanged");
        }
        try { writer.replaceFile(target, content, sources); }
        catch (Exception failure) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "文档更新未完成，原版本已保留或进入恢复流程；请检查索引状态", failure); }
        DocumentInfo info = list().stream().filter(d -> d.name().equals(name)).findFirst().orElseThrow();
        if (!info.status().equals("INDEXED")) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "索引状态未通过确认，请刷新检查");
        return withAction(info, same ? "repaired" : existed ? "updated" : "indexed");
    }
    private static DocumentInfo withAction(DocumentInfo d, String action) { return new DocumentInfo(d.name(), d.size(), d.updatedAt(), d.chunkCount(), d.status(), d.sourceFile(), d.deleteAllowed(), action); }

    public synchronized Map<String,Object> delete(String name) throws Exception {
        files.lock().writeLock().lock();
        try {
            Path target = existing(name);
            var sources = sourcesFor(name, index.statistics().keySet());
            if (sources.size() > 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "存在多个索引来源，不能安全移除");
            String source = sources.isEmpty() ? null : sources.get(0);
            var snapshot = source == null ? List.<Map<String,Object>>of() : index.snapshot(source);
            String id = UUID.randomUUID().toString();
            Path trash = files.root().resolve(".console-trash");
            if (Files.isSymbolicLink(trash)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "回收目录不合法");
            Files.createDirectories(trash);
            Path recovery = Files.createDirectory(trash.resolve(id));
            Files.writeString(recovery.resolve("manifest.json"), new Gson().toJson(Map.of("name", name, "source", source == null ? "" : source, "vectors", snapshot)));
            Files.move(target, recovery.resolve("document"));
            try { return Map.of("deleted", name, "recoveryId", id, "deletedChunks", source == null ? 0 : index.delete(source)); }
            catch (Exception failure) {
                try { index.restore(snapshot); } catch (Exception restore) { files.indexHealthy(false); failure.addSuppressed(restore); }
                finally { Files.move(recovery.resolve("document"), target); }
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "移除失败，源文件已恢复，请刷新状态", failure);
            }
        } finally { files.lock().writeLock().unlock(); }
    }
    public record DocumentInfo(String name, long size, long updatedAt, Long chunkCount, String status,
                               String sourceFile, boolean deleteAllowed, String uploadAction) {
        public DocumentInfo(String name,long size,long updatedAt,Long chunkCount,String status,String sourceFile,boolean deleteAllowed) {
            this(name,size,updatedAt,chunkCount,status,sourceFile,deleteAllowed,null);
        }
    }
}
