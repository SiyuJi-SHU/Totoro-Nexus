package org.example.platform;

import org.example.config.FileUploadConfig;
import org.example.service.KnowledgeFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class LexicalIndexTest {
    @TempDir Path root;
    private KnowledgeFiles files(){var c=new FileUploadConfig();c.setPath(root.toString());return new KnowledgeFiles(c);}
    private PlatformChunk chunk(String id,String version,String text){return new PlatformChunk(id,"doc-"+id,version,"data","design.md","实现",0,0,text.length(),text,null,null,null,"source");}
    @Test void exactIdentifierSearchSurvivesReopenAndScopeFilteringPrecedesRanking() throws Exception {
        var index=new LexicalIndex(files());
        index.prepare("v1",List.of(chunk("a","v1","DEXINED is the edge detector. 错误码 EHOSTUNREACH indicates no route.")));
        index.prepare("v2",List.of(chunk("b","v2","DEXINED DEXINED DEXINED hidden in another dataset.")));
        assertThat(index.search("DEXINED",List.of("v1"),1,false)).singleElement().extracting(PlatformChunk::version).isEqualTo("v1");
        assertThat(index.search("EHOSTUNREACH",List.of("v1"),3,false)).hasSize(1);
        assertThat(index.search("DEXINED",List.of(),3,false)).isEmpty();
        index.close();
        var reopened=new LexicalIndex(files());
        try {assertThat(reopened.count("v1")).isEqualTo(1);assertThat(reopened.search("design.md",List.of("v1"),3,true)).hasSize(1);}
        finally{reopened.close();}
    }
}
