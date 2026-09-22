package org.example.platform;

import org.example.service.KnowledgeFiles;
import org.example.service.DocumentReadingService;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.IOException;

/** Immutable source copies: source IDs and versions never come from a filesystem path supplied by a model. */
@Service
public class SourceStorage {
    private final Path root;
    public SourceStorage(KnowledgeFiles files) { this.root=files.root().resolve(".platform").resolve("sources"); }
    public Path path(String documentId,String version) {
        if (!documentId.matches("[A-Za-z0-9_-]{1,64}") || !version.matches("[A-Za-z0-9_-]{1,64}")) throw PlatformCatalog.bad("文档标识无效");
        Path target=root.resolve(documentId).resolve(version+".txt").normalize();
        if (!target.startsWith(root)) throw PlatformCatalog.bad("来源路径无效");
        for(Path current=target;current!=null && current.startsWith(root.getParent().getParent());current=current.getParent())
            if(Files.isSymbolicLink(current)) throw PlatformCatalog.bad("来源路径不能使用符号链接");
        return target;
    }
    public String write(String documentId,String version,String content) throws IOException {
        Path target=path(documentId,version);
        if(Files.exists(target)) {
            if (!Files.readString(target,StandardCharsets.UTF_8).equals(content)) throw new IOException("不可覆盖已经保存的来源版本");
        } else KnowledgeFiles.atomicWrite(target,content.getBytes(StandardCharsets.UTF_8));
        return documentId+"/"+version+".txt";
    }
    public DocumentReadingService.Source read(PlatformModels.DocumentVersion version) throws IOException {
        String content=Files.readString(path(version.documentId(),version.version()),StandardCharsets.UTF_8);
        if(!KnowledgeFiles.digest(content).equals(version.contentHash())) throw new IOException("来源内容与版本哈希不一致");
        return new DocumentReadingService.Source(version.documentId(),version.version(),version.path(),content);
    }
}
