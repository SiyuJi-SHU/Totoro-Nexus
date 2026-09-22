package org.example.service;

import org.example.config.*;
import org.example.constant.MilvusConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class VectorReplacementTest {
    @TempDir Path root;
    private KnowledgeFiles files;
    private final ConsoleDocumentIndexService index=mock(ConsoleDocumentIndexService.class);
    private final VectorEmbeddingService embeddings=mock(VectorEmbeddingService.class);
    private VectorIndexService service() {
        var config=new FileUploadConfig();config.setPath(root.toString());files=new KnowledgeFiles(config);
        var chunks=new DocumentChunkService();ReflectionTestUtils.setField(chunks,"chunkConfig",new DocumentChunkConfig());
        var writer=new VectorIndexService();ReflectionTestUtils.setField(writer,"knowledgeFiles",files);
        ReflectionTestUtils.setField(writer,"embeddingService",embeddings);ReflectionTestUtils.setField(writer,"documentIndex",index);
        ReflectionTestUtils.setField(writer,"chunkService",chunks);return writer;
    }
    @Test void embeddingFailureCannotDeleteOldVectorsOrOverwriteSource() throws Exception {
        Path target=root.resolve("doc.md");Files.writeString(target,"old content");var writer=service();
        when(embeddings.generateEmbedding(anyString())).thenThrow(new IllegalStateException("offline"));
        assertThatThrownBy(()->writer.replaceFile(target,"new content",List.of())).hasMessageContaining("offline");
        assertThat(Files.readString(target)).isEqualTo("old content");
        verifyNoInteractions(index);
    }
    @Test void failedCommitKeepsJournalUntilRecoveryRestoresOldFileAndRows() throws Exception {
        Path target=root.resolve("doc.md");Files.writeString(target,"old content");var writer=service();
        var old=Map.<String,Object>of("id","old-row","content","old content");
        when(embeddings.generateEmbedding(anyString())).thenReturn(Collections.nCopies(MilvusConstants.VECTOR_DIM,0.1f));
        when(index.snapshot(anyString())).thenReturn(List.of(old));
        doThrow(new IllegalStateException("write failed")).when(index).restore(anyList());
        assertThatThrownBy(()->writer.replaceFile(target,"new content",List.of())).hasMessageContaining("write failed");
        assertThat(files.indexHealthy()).isFalse();assertThat(Files.readString(target)).isEqualTo("old content");
        try(var journals=Files.list(root.resolve(".index-transactions"))){assertThat(journals.count()).isEqualTo(1);}
        doNothing().when(index).restore(anyList());
        writer.recoverPending();
        assertThat(files.indexHealthy()).isTrue();
        try(var journals=Files.list(root.resolve(".index-transactions"))){assertThat(journals.count()).isZero();}
        verify(index,atLeastOnce()).restore(argThat(rows->rows.size()==1&&"old-row".equals(rows.get(0).get("id"))));
        verify(index,never()).deleteIds(List.of("old-row"));
    }
}

