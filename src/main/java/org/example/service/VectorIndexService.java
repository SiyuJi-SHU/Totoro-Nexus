package org.example.service;

import lombok.Getter;
import lombok.Setter;
import org.example.constant.MilvusConstants;
import org.example.dto.DocumentChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 向量索引服务
 * 负责读取文件、生成向量、存储到 Milvus
 */
@Service
public class VectorIndexService {

    private static final Logger logger = LoggerFactory.getLogger(VectorIndexService.class);

    @Autowired
    private VectorEmbeddingService embeddingService;

    @Autowired
    private DocumentChunkService chunkService;

    @Autowired private KnowledgeFiles knowledgeFiles;
    @Autowired private ConsoleDocumentIndexService documentIndex;

    @Value("${file.upload.path}")
    private String uploadPath;

    /**
     * 索引指定目录下的所有文件
     * 
     * @param directoryPath 目录路径（可选，默认使用配置的上传目录）
     * @return 索引结果  这里可以优化：定时重建目录下所有文件的索引
     */
    public IndexingResult indexDirectory(String directoryPath) {
        IndexingResult result = new IndexingResult();
        result.setStartTime(LocalDateTime.now());

        try {
            // 使用指定目录或默认上传目录
            String targetPath = (directoryPath != null && !directoryPath.trim().isEmpty()) 
                    ? directoryPath : uploadPath;
                    
            Path dirPath = Paths.get(targetPath).normalize();
            File directory = dirPath.toFile();
            
            if (!directory.exists() || !directory.isDirectory()) {
                throw new IllegalArgumentException("目录不存在或不是有效目录: " + targetPath);
            }

            result.setDirectoryPath(directory.getAbsolutePath());

            // 获取所有支持的文件
            File[] files = directory.listFiles((dir, name) -> 
                name.endsWith(".txt") || name.endsWith(".md")
            );

            if (files == null || files.length == 0) {
                logger.warn("目录中没有找到支持的文件: {}", targetPath);
                result.setTotalFiles(0);
                result.setSuccess(true);
                result.setEndTime(LocalDateTime.now());
                return result;
            }

            result.setTotalFiles(files.length);
            logger.info("开始索引目录: {}, 找到 {} 个文件", targetPath, files.length);

            // 遍历并索引每个文件
            for (File file : files) {
                try {
                    indexSingleFile(file.getAbsolutePath());
                    result.incrementSuccessCount();
                    logger.info("✓ 文件索引成功: {}", file.getName());
                } catch (Exception e) {
                    result.incrementFailCount();
                    result.addFailedFile(file.getAbsolutePath(), e.getMessage());
                    logger.error("✗ 文件索引失败: {}", file.getName(), e);
                }
            }

            result.setSuccess(result.getFailCount() == 0);
            result.setEndTime(LocalDateTime.now());

            logger.info("目录索引完成: 总数={}, 成功={}, 失败={}", 
                result.getTotalFiles(), result.getSuccessCount(), result.getFailCount());

            return result;

        } catch (Exception e) {
            logger.error("索引目录失败", e);
            result.setSuccess(false);
            result.setErrorMessage(e.getMessage());
            result.setEndTime(LocalDateTime.now());
            return result;
        }
    }

    /**
     * 索引单个文件
     * 
     * @param filePath 文件路径
     * @throws Exception 索引失败时抛出异常
     */
    public void indexSingleFile(String filePath) throws Exception {
        Path target = Paths.get(filePath).toAbsolutePath().normalize();
        String name = knowledgeFiles.name(target);
        knowledgeFiles.resolve(name);
        var sources = ConsoleDocumentService.matchingSources(name, documentIndex.statistics().keySet());
        replaceFile(target, Files.readString(target), sources);
    }

    /** All embeddings are prepared before touching committed vectors or the source file. */
    public synchronized void replaceFile(Path target, String content, List<String> sources) throws Exception {
        knowledgeFiles.resolve(knowledgeFiles.name(target));
        recoverPending();
        var chunks = chunkService.chunkDocument(content, target.toString());
        if (chunks.isEmpty()) throw new IllegalArgumentException("文档没有可索引的内容");
        String revision = UUID.randomUUID().toString();
        String hash = KnowledgeFiles.digest(content);
        var gson = new com.google.gson.Gson();
        List<Map<String,Object>> prepared = new ArrayList<>();
        for (DocumentChunk chunk : chunks) {
            ChatModelFactory.checkCancelled();
            var vector = embeddingService.generateEmbedding(chunk.getContent());
            if (vector.size() != MilvusConstants.VECTOR_DIM) throw new IllegalStateException("向量维度与集合不匹配");
            var metadata = buildMetadata(target.toString(), chunk, chunks.size());
            metadata.put("_content_hash", hash);
            metadata.put("_revision", revision);
            prepared.add(Map.of("id", revision + "-" + chunk.getChunkIndex(), "content", chunk.getContent(),
                    "vector", vector, "metadata", gson.toJsonTree(metadata).getAsJsonObject()));
        }
        knowledgeFiles.lock().writeLock().lock();
        Path journal = null;
        try {
            List<Map<String,Object>> oldRows = new ArrayList<>();
            Set<String> exactSources = new LinkedHashSet<>(sources);
            exactSources.add(target.toString().replace('\\','/'));
            for (String source : exactSources) oldRows.addAll(documentIndex.snapshot(source));
            boolean existed = Files.exists(target);
            Map<String,Object> recovery = new LinkedHashMap<>();
            recovery.put("name", knowledgeFiles.name(target)); recovery.put("existed", existed);
            recovery.put("oldContent", existed ? Base64.getEncoder().encodeToString(Files.readAllBytes(target)) : "");
            recovery.put("oldRows", oldRows);
            recovery.put("newIds", prepared.stream().map(row -> row.get("id").toString()).toList());
            Path directory = knowledgeFiles.root().resolve(".index-transactions");
            if (Files.isSymbolicLink(directory)) throw new IllegalStateException("索引恢复目录不合法");
            journal = directory.resolve(revision + ".json");
            KnowledgeFiles.atomicWrite(journal, gson.toJson(recovery).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            documentIndex.restore(prepared);
            documentIndex.deleteIds(oldRows.stream().map(row -> row.get("id").toString()).distinct().toList());
            if (documentIndex.snapshot(target.toString().replace('\\','/')).size() != prepared.size())
                throw new IllegalStateException("新索引分块数未通过确认");
            KnowledgeFiles.atomicWrite(target, content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Files.delete(journal);
            journal = null;
            knowledgeFiles.indexHealthy(true);
        } catch (Exception failure) {
            if (journal != null && Files.exists(journal)) {
                try { rollback(journal); }
                catch (Exception recoveryFailure) { knowledgeFiles.indexHealthy(false); if (failure != recoveryFailure) failure.addSuppressed(recoveryFailure); }
            }
            throw failure;
        } finally { knowledgeFiles.lock().writeLock().unlock(); }
    }

    @jakarta.annotation.PostConstruct
    public void recoverAtStartup() {
        try { recoverPending(); }
        catch (Exception e) {
            knowledgeFiles.indexHealthy(false);
            logger.error("索引事务恢复失败，已暂停向量检索；原文检索仍可使用", e);
        }
    }

    public synchronized void recoverPending() throws Exception {
        Path directory = knowledgeFiles.root().resolve(".index-transactions");
        if (!Files.exists(directory)) return;
        if (Files.isSymbolicLink(directory) || Files.isSymbolicLink(knowledgeFiles.root())) throw new IllegalStateException("索引恢复目录不合法");
        knowledgeFiles.lock().writeLock().lock();
        try (var journals = Files.list(directory)) {
            for (Path journal : journals.filter(p -> p.getFileName().toString().endsWith(".json") && Files.isRegularFile(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)).toList())
                rollback(journal);
            knowledgeFiles.indexHealthy(true);
        } finally { knowledgeFiles.lock().writeLock().unlock(); }
    }

    private void rollback(Path journal) throws Exception {
        var gson = new com.google.gson.Gson();
        java.lang.reflect.Type type = new com.google.gson.reflect.TypeToken<Map<String,Object>>(){}.getType();
        Map<String,Object> state = gson.fromJson(Files.readString(journal), type);
        Path target = knowledgeFiles.resolve(state.get("name").toString());
        @SuppressWarnings("unchecked") List<Map<String,Object>> oldRows = (List<Map<String,Object>>)state.get("oldRows");
        @SuppressWarnings("unchecked") List<String> ids = (List<String>)state.get("newIds");
        Exception failure = null;
        try { documentIndex.restore(oldRows); } catch (Exception e) { failure = e; }
        try { documentIndex.deleteIds(ids); } catch (Exception e) { if (failure == null) failure = e; else if (failure != e) failure.addSuppressed(e); }
        try {
            if (Boolean.TRUE.equals(state.get("existed"))) KnowledgeFiles.atomicWrite(target, Base64.getDecoder().decode(state.get("oldContent").toString()));
            else Files.deleteIfExists(target);
        } catch (Exception e) { if (failure == null) failure = e; else if (failure != e) failure.addSuppressed(e); }
        if (failure != null) throw failure;
        Files.delete(journal);
    }

    private Map<String, Object> buildMetadata(String filePath, DocumentChunk chunk, int totalChunks) {
        Map<String, Object> metadata = new HashMap<>();
        
        // 标准化路径：使用统一的路径分隔符（正斜杠）用于存储，确保跨平台一致性
        Path path = Paths.get(filePath).normalize();
        String normalizedPath = path.toString().replace(File.separator, "/");
        
        // 文件信息
        Path fileName = path.getFileName();
        String fileNameStr = fileName != null ? fileName.toString() : "";
        String extension = "";
        int dotIndex = fileNameStr.lastIndexOf('.');
        if (dotIndex > 0) {
            extension = fileNameStr.substring(dotIndex);
        }
        
        metadata.put("_source", normalizedPath);
        metadata.put("_extension", extension);
        metadata.put("_file_name", fileNameStr);
        
        // 分片信息
        metadata.put("chunkIndex", chunk.getChunkIndex());
        metadata.put("totalChunks", totalChunks);
        
        // 标题信息
        if (chunk.getTitle() != null && !chunk.getTitle().isEmpty()) {
            metadata.put("title", chunk.getTitle());
        }
        
        return metadata;
    }

    /**
     * 索引结果类
     */
    @Getter
    public static class IndexingResult {
        @Setter
        private boolean success;
        @Setter
        private String directoryPath;
        @Setter
        private int totalFiles;
        private int successCount;
        private int failCount;
        @Setter
        private LocalDateTime startTime;
        @Setter
        private LocalDateTime endTime;
        @Setter
        private String errorMessage;
        private Map<String, String> failedFiles = new HashMap<>();

        public void incrementSuccessCount() {
            this.successCount++;
        }

        public void incrementFailCount() {
            this.failCount++;
        }

        public long getDurationMs() {
            if (startTime != null && endTime != null) {
                return java.time.Duration.between(startTime, endTime).toMillis();
            }
            return 0;
        }

        public void addFailedFile(String filePath, String error) {
            this.failedFiles.put(filePath, error);
        }
    }
}
