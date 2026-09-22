package org.example.service;

import org.example.config.*;
import org.example.dto.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RepairRegressionTest {
    @TempDir Path root;
    private DocumentChunkService chunks(int size, int overlap) {
        var config = new DocumentChunkConfig(); config.setMaxSize(size); config.setOverlap(overlap);
        var service = new DocumentChunkService(); ReflectionTestUtils.setField(service, "chunkConfig", config); return service;
    }
    private KnowledgeFiles files() { var config = new FileUploadConfig(); config.setPath(root.toString()); return new KnowledgeFiles(config); }

    @Test void longParagraphsRemainBoundedAndEveryChunkComesFromOriginalText() {
        String content = "# Incident\n\n" + "a long paragraph with actual spaces. ".repeat(100)
                + "\n\n" + "next paragraph. ".repeat(40);
        var pieces = chunks(120, 30).chunkDocument(content, "incident.md");
        assertThat(pieces.size()).isGreaterThan(10);
        for (var piece : pieces) {
            assertThat(piece.getContent().length()).isLessThanOrEqualTo(120);
            assertThat(content).contains(piece.getContent());
        }
        assertThat(pieces.get(pieces.size()-1).getContent()).endsWith("next paragraph.");
    }
    @Test void legacyOverlapJoinsResolveToSourceAndInventedTextDoesNot() throws Exception {
        String source = "# Incident\n\nFirst paragraph ends here.\n\nCheck the target address before restarting.";
        String legacy = "paragraph ends here.Check the target address";
        assertThat(KnowledgeFiles.originalExcerpt(source, legacy)).contains("paragraph ends here.\n\nCheck the target address");
        assertThat(KnowledgeFiles.originalExcerpt(source, "paragraph ends here.Delete all data")).isEmpty();
        Files.writeString(root.resolve("incident.md"), source);
        var keyword = new KeywordSearchService(files(), chunks(120,30));
        var hit = new VectorSearchService.SearchResult(); hit.setSourceFile("incident.md"); hit.setContent(legacy);
        assertThat(keyword.validateVectors(List.of(hit))).singleElement().satisfies(d -> assertThat(source).contains(d.content()));
    }
    @Test void repeatedUploadWithLegacyJoinDoesNotReembedOrReplaceHealthyIndex() throws Exception {
        String content = "# Incident\n\nFirst paragraph ends here.\n\nCheck the target address.";
        Files.writeString(root.resolve("incident.md"),content);
        var index = mock(ConsoleDocumentIndexService.class); var writer = mock(VectorIndexService.class);
        when(index.statistics()).thenReturn(Map.of("incident.md",new ConsoleDocumentIndexService.SourceStats(1,1)));
        when(index.snapshot("incident.md")).thenReturn(List.of(Map.of("content","paragraph ends here.Check the target address.")));
        var service = new ConsoleDocumentService(files(),index,writer);
        var result = service.upload(new MockMultipartFile("file","incident.md","text/markdown",content.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(result.uploadAction()).isEqualTo("unchanged");
        verify(writer).recoverPending(); verifyNoMoreInteractions(writer);
    }
    @Test void sourceExpansionCannotAppendAnEntireWeaklyMatchedRunbook() throws Exception {
        StringBuilder book = new StringBuilder("# Error\n\nActual error observed.\n\n");
        for(int i=0;i<30;i++) book.append("## Troubleshooting ").append(i).append("\n\nDifferent step ").append(i).append(".\n\n");
        Files.writeString(root.resolve("incident.md"),book.toString());
        var keyword = new KeywordSearchService(files(),chunks(120,30));
        var first = KeywordSearchService.document("incident.md","Error",0,"# Error\n\nActual error observed.","vector",0.1,null,null);
        assertThat(keyword.expand(List.of(first))).hasSizeLessThanOrEqualTo(3);
    }
    @Test void absentLogsCannotBecomePositiveExclusionEvidence() {
        var a = DiagnosisEvidenceTest.analysis(); var finding = a.findings().get(0);
        var exclusion = new GroundedAnalysis.Finding("未提到磁盘错误，因此排除磁盘故障","observation",finding.citations());
        var report = new DiagnosticReportService();
        var validated = report.validate(report.encode(new GroundedAnalysis(true,a.service(),List.of(finding),List.of(exclusion),a.actions(),List.of())),
                DiagnosisEvidenceTest.snapshot(),List.of(DiagnosisEvidenceTest.doc()));
        assertThat(validated.analysis().relevant()).isTrue();
        assertThat(validated.analysis().contradictions()).isEmpty();
    }
    @Test void explicitOptOutDoesNotCatchRequestsToDiagnoseOnlyThisService() {
        assertThat(SmartAiOpsService.explicitNonDiagnostic("请介绍一下系统功能，不要诊断告警")).isTrue();
        assertThat(SmartAiOpsService.explicitNonDiagnostic("不要分析其他服务，只诊断这次PostgreSQL invalid page")).isFalse();
    }
    @Test void oneUnsupportedCorrectionDoesNotEraseAnotherGroundedFinding() {
        var reports = new DiagnosticReportService(); var a = DiagnosisEvidenceTest.analysis();
        var input = reports.auditInput(List.of(a,a), List.of(DiagnosisEvidenceTest.doc()));
        String id = DiagnosisEvidenceTest.doc().id();
        String response = "{\"findings\":[{\"index\":0,\"text\":\"观察到读取失败，原因待验证\",\"certainty\":\"hypothesis\",\"evidence\":[\"L1\",\""+id+"\"]},{\"index\":0,\"text\":\"发生999次失败\",\"certainty\":\"observation\",\"evidence\":[\"L1\"]}],\"actions\":[0],\"contradictions\":[],\"missingEvidence\":[]}";
        var result = reports.validateSelection(response,DiagnosisEvidenceTest.snapshot(),input);
        assertThat(result.needsReview()).isFalse();
        assertThat(result.analysis().findings()).singleElement().satisfies(f -> assertThat(f.text()).doesNotContain("999"));
    }
}
