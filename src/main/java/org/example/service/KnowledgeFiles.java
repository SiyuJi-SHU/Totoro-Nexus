package org.example.service;

import org.example.config.FileUploadConfig;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Confined source-file access, also available when Milvus or Embedding is offline. */
@Service
public class KnowledgeFiles {
    public static final long MAX_BYTES = 5L * 1024 * 1024;
    private final Path root;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    private volatile boolean indexHealthy = true;
    public KnowledgeFiles(FileUploadConfig config) { root = Path.of(config.getPath()).toAbsolutePath().normalize(); }
    public Path root() { return root; }
    public ReentrantReadWriteLock lock() { return lock; }
    public boolean indexHealthy() { return indexHealthy; }
    public void indexHealthy(boolean healthy) { indexHealthy = healthy; }
    public Path resolve(String name) throws IOException {
        if (name == null || name.isBlank() || name.contains("\\") || name.contains(":") || name.indexOf('\0') >= 0)
            throw badPath();
        Path path = root.resolve(name).normalize();
        if (name.startsWith("sessions/")) throw badPath();
        if (!path.startsWith(root) || path.equals(root) || !supported(path) || Files.isSymbolicLink(root)) throw badPath();
        Path current = root;
        for (Path part : root.relativize(path)) {
            if (part.toString().startsWith(".")) throw badPath();
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw badPath();
        }
        if (Files.exists(path) && !path.toRealPath().startsWith(root.toRealPath())) throw badPath();
        return path;
    }
    private static ResponseStatusException badPath() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "文档路径越界或格式不支持");
    }
    public static boolean supported(Path path) { return path.toString().toLowerCase(Locale.ROOT).matches(".*\\.(md|txt)$"); }
    public List<Path> paths() throws IOException {
        if (!Files.exists(root)) return List.of();
        List<Path> found = new ArrayList<>();
        Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), 12, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs) {
                return !dir.equals(root) && dir.getFileName().toString().startsWith(".")
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
                if (attrs.isRegularFile() && supported(file)) {
                    try { if (resolve(name(file)).equals(file)) found.add(file); } catch (Exception ignored) {}
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return found.stream().sorted().toList();
    }
    public String name(Path path) { return root.relativize(path).toString().replace('\\', '/'); }
    public List<Source> sources() throws IOException {
        lock.readLock().lock();
        try {
            List<Source> result = new ArrayList<>();
            for (Path p : paths()) {
                if (Files.size(p) > MAX_BYTES) continue;
                String content = Files.readString(p, StandardCharsets.UTF_8);
                result.add(new Source(name(p), content));
            }
            return List.copyOf(result);
        } finally { lock.readLock().unlock(); }
    }
    public static String digest(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static void atomicWrite(Path target, byte[] content) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), ".pending-", ".tmp");
        try {
            try (var channel = java.nio.channels.FileChannel.open(temp, StandardOpenOption.WRITE)) {
                var bytes = java.nio.ByteBuffer.wrap(content);
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
    public record Source(String name, String content) {}

    /** Resolve legacy chunks whose paragraph/overlap join lost whitespace back to actual source bytes.
     * Only whitespace may differ; every non-whitespace character must occur contiguously in order.
     * Return source text, never publish the malformed stored join as evidence. */
    public static Optional<String> originalExcerpt(String source, String chunk) {
        if (source == null || chunk == null || chunk.isBlank()) return Optional.empty();
        if (source.contains(chunk)) return Optional.of(chunk);
        String needle = chunk.replaceAll("\\s+", "");
        if (needle.isEmpty()) return Optional.empty();
        StringBuilder compact = new StringBuilder(source.length());
        int[] offsets = new int[source.length()];
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == 11) continue;
            offsets[compact.length()] = i;
            compact.append(c);
        }
        int start = compact.indexOf(needle);
        return start < 0 ? Optional.empty() : Optional.of(source.substring(offsets[start], offsets[start + needle.length() - 1] + 1));
    }
}
