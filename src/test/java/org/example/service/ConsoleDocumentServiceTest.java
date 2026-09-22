package org.example.service;

import org.example.config.FileUploadConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConsoleDocumentServiceTest {
    @TempDir Path root;
    private final ConsoleDocumentIndexService index = mock(ConsoleDocumentIndexService.class);
    private final VectorIndexService writer = mock(VectorIndexService.class);
    private ConsoleDocumentService service() {
        var config = new FileUploadConfig(); config.setPath(root.toString());
        return new ConsoleDocumentService(config, index, writer);
    }
    private String source(String name) { return root.resolve(name).toString().replace('\\', '/'); }

    @Test void customUploadDirectoryMatchesExactSourceAndUnreachableIndexIsUnknown() throws Exception {
        Files.writeString(root.resolve("doc.md"), "content");
        when(index.statistics()).thenReturn(Map.of(source("doc.md"), new ConsoleDocumentIndexService.SourceStats(3, 3)));
        var d = service().list().get(0);
        assertThat(d.chunkCount()).isEqualTo(3); assertThat(d.status()).isEqualTo("INDEXED");
        when(index.statistics()).thenThrow(new IllegalStateException("offline"));
        d = service().list().get(0);
        assertThat(d.chunkCount()).isNull(); assertThat(d.status()).isEqualTo("INDEX_UNAVAILABLE");
        assertThat(d.deleteAllowed()).isFalse();
    }

    @Test void hostIndexedSourceMatchesMountedDocumentByFileName() throws Exception {
        Files.writeString(root.resolve("doc.md"), "content");
        when(index.statistics()).thenReturn(Map.of(
                "D:/Simon/downloads/SimonWiki/Project/agent-core/aiops-docs/opensource/doc.md",
                new ConsoleDocumentIndexService.SourceStats(3, 3)));
        var d = service().list().get(0);
        assertThat(d.chunkCount()).isEqualTo(3);
        assertThat(d.status()).isEqualTo("INDEXED");
        assertThat(d.deleteAllowed()).isTrue();
    }

    @Test void deletionBacksUpFileAndVectorsAndDeletesOnlyExactSource() throws Exception {
        Files.writeString(root.resolve("doc.md"), "original");
        var rows = List.<Map<String,Object>>of(Map.of("id", "chunk-id", "content", "original"));
        when(index.statistics()).thenReturn(Map.of(source("doc.md"), new ConsoleDocumentIndexService.SourceStats(1, 1)));
        when(index.snapshot(source("doc.md"))).thenReturn(rows);
        when(index.delete(source("doc.md"))).thenReturn(1L);
        var result = service().delete("doc.md");
        assertThat(root.resolve("doc.md")).doesNotExist();
        Path recovery = root.resolve(".console-trash").resolve(result.get("recoveryId").toString());
        assertThat(Files.readString(recovery.resolve("document"))).isEqualTo("original");
        assertThat(Files.readString(recovery.resolve("manifest.json"))).contains("chunk-id", "doc.md");
        assertThat(result.get("deletedChunks")).isEqualTo(1L);
        verify(index).delete(source("doc.md")); verify(index, never()).restore(anyList());
        assertThat(service().list()).isEmpty();
    }

    @Test void deletionFailureRestoresSourceAndVectorsAndAmbiguousSourcesAreRefused() throws Exception {
        Files.writeString(root.resolve("doc.md"), "original");
        var rows = List.<Map<String,Object>>of(Map.of("id", "chunk-id"));
        when(index.statistics()).thenReturn(Map.of(source("doc.md"), new ConsoleDocumentIndexService.SourceStats(1, 1)));
        when(index.snapshot(source("doc.md"))).thenReturn(rows);
        when(index.delete(source("doc.md"))).thenThrow(new IllegalStateException("lost connection"));
        assertThatThrownBy(() -> service().delete("doc.md")).isInstanceOf(ResponseStatusException.class).hasMessageContaining("503");
        assertThat(Files.readString(root.resolve("doc.md"))).isEqualTo("original");
        verify(index).restore(rows);
        reset(index);
        when(index.statistics()).thenReturn(Map.of(source("doc.md"), new ConsoleDocumentIndexService.SourceStats(1, 1),
                "/old/uploads/doc.md", new ConsoleDocumentIndexService.SourceStats(1, 1)));
        assertThatThrownBy(() -> service().delete("doc.md")).isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        verify(index, never()).delete(anyString()); assertThat(root.resolve("doc.md")).exists();
    }

    @Test void sameContentSkipsHealthyIndexAndChangedContentUsesSharedReplacement() throws Exception {
        Files.writeString(root.resolve("doc.md"),"original");
        var stats=Map.of(source("doc.md"),new ConsoleDocumentIndexService.SourceStats(1,1));
        when(index.statistics()).thenReturn(stats);
        when(index.snapshot(source("doc.md"))).thenReturn(List.of(Map.of("id","old","content","original")));
        var service=service();
        var original=new MockMultipartFile("file","doc.md","text/markdown","original".getBytes());
        assertThat(service.upload(original).uploadAction()).isEqualTo("unchanged");
        verify(writer,never()).replaceFile(any(),any(),any());
        doAnswer(i->{Files.writeString(i.getArgument(0),i.getArgument(1));return null;}).when(writer).replaceFile(any(),anyString(),anyList());
        var changed=new MockMultipartFile("file","doc.md","text/markdown","updated content".getBytes());
        assertThat(service.upload(changed).uploadAction()).isEqualTo("updated");
        assertThat(Files.readString(root.resolve("doc.md"))).isEqualTo("updated content");
        doThrow(new IllegalStateException("embedding offline")).when(writer).replaceFile(any(),anyString(),anyList());
        assertThatThrownBy(()->service.upload(original)).hasMessageContaining("503");
        assertThat(Files.readString(root.resolve("doc.md"))).isEqualTo("updated content");
    }
    @Test void identicalContentWithMissingChunksRepairsInsteadOfSkipping() throws Exception {
        Files.writeString(root.resolve("doc.md"),"original");
        when(index.statistics()).thenReturn(
            Map.of(source("doc.md"),new ConsoleDocumentIndexService.SourceStats(1,2)),
            Map.of(source("doc.md"),new ConsoleDocumentIndexService.SourceStats(2,2)));
        when(index.snapshot(source("doc.md"))).thenReturn(List.of(Map.of("id","old","content","original")));
        assertThat(service().upload(new MockMultipartFile("file","doc.md","text/markdown","original".getBytes())).uploadAction()).isEqualTo("repaired");
        verify(writer).replaceFile(eq(root.resolve("doc.md")),eq("original"),eq(List.of(source("doc.md"))));
    }

    @Test void refusesPathTraversalHiddenFilesAndInvalidUploads() throws Exception {
        var service = service();
        for (String name : List.of("../outside.md", "sub/../../outside.md", ".console-trash/file.md", "bad.exe", "sub\\file.md"))
            assertThatThrownBy(() -> service.preview(name)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
        assertThatThrownBy(() -> service.upload(new MockMultipartFile("file", "../outside.md", "text/markdown", "x".getBytes())))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
        assertThatThrownBy(() -> service.upload(new MockMultipartFile("file", "empty.md", "text/markdown", new byte[0])))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
        verifyNoInteractions(index, writer);
    }
}
