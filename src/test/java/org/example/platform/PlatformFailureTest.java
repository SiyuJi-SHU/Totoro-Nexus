package org.example.platform;

import org.example.config.FileUploadConfig;
import org.example.service.*;
import org.example.dto.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.mock.web.MockMultipartFile;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlatformFailureTest {
    @TempDir Path root;private PlatformCatalog catalog;private DocumentCatalog documents;private KnowledgeFiles files;
    private JdbcTemplate db;private LexicalIndex lexical;private SourceStorage sources;
    @BeforeEach void setup()throws Exception {
        var data=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__platform_catalog.sql"), new ClassPathResource("db/migration/V5__session_origin.sql")).execute(data);
        db=new JdbcTemplate(data);catalog=new PlatformCatalog(db,new ObjectMapper());catalog.initializeDefaults();documents=new DocumentCatalog(db,catalog);
        var config=new FileUploadConfig();config.setPath(root.toString());files=new KnowledgeFiles(config);lexical=new LexicalIndex(files);sources=new SourceStorage(files);
    }
    @AfterEach void close()throws Exception{lexical.close();}
    @Test void internalSourceVersionsAndBootstrapSecretNeverEnterLegacyKnowledge()throws Exception {
        Files.createDirectories(root.resolve(".platform/sources/document"));Files.writeString(root.resolve(".platform/admin-initial-password.txt"),"secret-sentinel");Files.writeString(root.resolve(".platform/sources/document/version.txt"),"internal");
        Files.createDirectories(root.resolve("runbooks"));Files.writeString(root.resolve("runbooks/visible.md"),"public");
        assertThat(files.sources()).containsExactly(new KnowledgeFiles.Source("runbooks/visible.md","public"));
        assertThatThrownBy(()->files.resolve(".platform/admin-initial-password.txt")).hasMessageContaining("路径");
    }
    @Test void vectorFailureDuringUpdateKeepsOldLexicalAndSourceSnapshotUsable()throws Exception {
        var vectors=mock(VersionedVectorIndex.class);var chunker=new DocumentChunkService();
        var chunkConfig=new org.example.config.DocumentChunkConfig();chunkConfig.setMaxSize(1200);chunkConfig.setOverlap(200);ReflectionTestUtils.setField(chunker,"chunkConfig",chunkConfig);
        var ingestion=new KnowledgeIngestion(documents,sources,chunker,lexical,vectors,files,mock(ConsoleDocumentIndexService.class));
        var first=ingestion.ingest("existing-data","manual.md","# Recovery\nDEXINED original recovery instructions.\n");
        var old=documents.active(Set.of("existing-data")).get(0);
        doThrow(new IllegalStateException("injected vector failure")).when(vectors).prepare(anyList());
        assertThatThrownBy(()->ingestion.ingest("existing-data","manual.md","# Recovery\nNew different instructions.\n")).hasMessageContaining("原版本仍可用");
        assertThat(documents.active(Set.of("existing-data"))).singleElement().extracting(PlatformModels.DocumentVersion::version).isEqualTo(old.version());
        assertThat(lexical.search("DEXINED",List.of(old.version()),3,false)).hasSize(1);
        assertThat(sources.read(old).content()).contains("original recovery");
        assertThat(documents.list("existing-data")).singleElement().extracting(PlatformModels.DocumentInfo::status).isEqualTo("INDEX_FAILED");
    }
    @Test void duplicateHealthyUploadDoesNotReindexAndRepairBuildsNewVersion()throws Exception {
        var vectors=mock(VersionedVectorIndex.class);var chunker=new DocumentChunkService();
        var chunkConfig=new org.example.config.DocumentChunkConfig();chunkConfig.setMaxSize(1200);chunkConfig.setOverlap(200);ReflectionTestUtils.setField(chunker,"chunkConfig",chunkConfig);
        var ingestion=new KnowledgeIngestion(documents,sources,chunker,lexical,vectors,files,mock(ConsoleDocumentIndexService.class));
        String text="# A\nDEXINED documented here.\n";ingestion.ingest("existing-data","a.md",text);
        var old=documents.active(Set.of("existing-data")).get(0);when(vectors.count(old.version())).thenReturn(old.chunkCount());
        assertThat(ingestion.ingest("existing-data","a.md",text).action()).isEqualTo("unchanged");verify(vectors,times(1)).prepare(anyList());
        when(vectors.count(old.version())).thenReturn(0);
        assertThat(ingestion.ingest("existing-data","a.md",text).action()).isEqualTo("repaired");
        assertThat(documents.active(Set.of("existing-data"))).singleElement().extracting(PlatformModels.DocumentVersion::version).isNotEqualTo(old.version());
    }
    @Test void folderUploadPreservesRelativePathsForAllSupportedDocumentTypes()throws Exception {
        var vectors=mock(VersionedVectorIndex.class);var chunker=new DocumentChunkService();
        var chunkConfig=new org.example.config.DocumentChunkConfig();chunkConfig.setMaxSize(1200);chunkConfig.setOverlap(200);ReflectionTestUtils.setField(chunker,"chunkConfig",chunkConfig);
        var ingestion=new KnowledgeIngestion(documents,sources,chunker,lexical,vectors,files,mock(ConsoleDocumentIndexService.class));
        ingestion.upload("existing-data","handbook/service-a",new MockMultipartFile("file","readme.md","text/markdown","# A\nService A recovery".getBytes(StandardCharsets.UTF_8)));
        ingestion.upload("existing-data","handbook/service-b",new MockMultipartFile("file","readme.txt","text/plain","Service B recovery".getBytes(StandardCharsets.UTF_8)));
        assertThat(documents.list("existing-data")).extracting(PlatformModels.DocumentInfo::path)
                .containsExactly("handbook/service-a/readme.md","handbook/service-b/readme.txt");
        assertThatThrownBy(()->ingestion.upload("existing-data","handbook",new MockMultipartFile("file","ignored.pdf","application/pdf","not supported".getBytes(StandardCharsets.UTF_8))))
                .hasMessageContaining("Markdown/TXT");
    }
    @Test void sessionOwnershipConfigurationAndTerminalCancellationPersist() {
        var runs=new AgentRunStore(db,catalog);var session=runs.openSession("alice","oncall",null,"test");
        assertThatThrownBy(()->runs.session(session.id(),"bob")).hasMessageContaining("不存在");
        var run=runs.create(session,Map.of("question","first"),Map.of("snapshot","fixed"));
        assertThatThrownBy(()->runs.create(session,Map.of(),Map.of())).hasMessageContaining("已有");
        assertThat(runs.finish(run.id(),"cancelled",null,"cancelled")).isTrue();
        assertThat(runs.finish(run.id(),"completed",Map.of("answer","late"),null)).isFalse();
        var reopened=new AgentRunStore(db,catalog);assertThat(reopened.get(run.id()).status()).isEqualTo("cancelled");
        assertThat(reopened.get(run.id()).contextJson()).contains("fixed");assertThat(reopened.events(run.id(),0)).hasSize(1);
    }
    @Test void inventedCommandDoesNotEraseIndependentSupportedConclusion()throws Exception {
        var service=new AgentAnswerService(new ObjectMapper(),new DiagnosticReportService());
        var evidence=new AgentToolRegistry.Evidence("D-test","doc","v1","guide.md","Recovery",0,50,"DEXINED is an edge detector. Check the documented model settings.","document");
        String output="""
          {"findings":[{"text":"DEXINED is an edge detector.","certainty":"supported","citations":[{"id":"D-test","quote":"DEXINED is an edge detector."}]}],
          "actions":[{"text":"Run cleanup","command":"rm -rf /data","prerequisites":"","citations":[{"id":"D-test","quote":"Check the documented model settings."}]}],"missingEvidence":[]}
          """;
        var result=service.validate(output,Map.of("D-test",evidence),null);
        assertThat(result.answer().findings()).hasSize(1);assertThat(result.answer().actions()).isEmpty();assertThat(result.rejectedItems()).isEqualTo(1);
    }
    @Test void exactQuoteRepairsMistypedEvidenceIdOnlyWhenItsSourceIsUnambiguous()throws Exception {
        var service=new AgentAnswerService(new ObjectMapper(),new DiagnosticReportService());
        String source="Check `select * from pg_replication_slots` before changing replication slots.";
        var shortChunk=new AgentToolRegistry.Evidence("D-short","postgres","v1","postgres.md","Checks",0,source.length(),source,"document");
        var fullRead=new AgentToolRegistry.Evidence("D-full","postgres","v1","postgres.md","Full",0,source.length()+20,"Intro. "+source+" More detail.","document");
        String output="""
          {"findings":[],"actions":[{"text":"Check replication slots","command":"select * from pg_replication_slots;","prerequisites":"","citations":[{"id":"D-fllu","quote":"Check `select * from pg_replication_slots` before changing replication slots."}]}],"missingEvidence":[]}
          """;
        var result=service.validate(output,Map.of("D-short",shortChunk,"D-full",fullRead),null);
        assertThat(result.rejectedItems()).isZero();
        assertThat(result.answer().actions()).singleElement().satisfies(action->{
            assertThat(action.command()).isEqualTo("select * from pg_replication_slots;");
            assertThat(action.citations()).singleElement().extracting(GroundedAnalysis.Citation::id).isEqualTo("D-full");
        });

        var other=new AgentToolRegistry.Evidence("D-other","other","v2","other.md","Other",0,source.length(),source,"document");
        assertThat(service.validate(output,Map.of("D-short",shortChunk,"D-other",other),null).answer().actions()).isEmpty();
    }
}

