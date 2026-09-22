package org.example.service;

import org.example.config.*;
import org.example.dto.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class DiagnosisEvidenceTest {
    @TempDir Path root;
    static IncidentSnapshot snapshot() {
        return new IncidentSnapshot(UUID.randomUUID().toString(), "s1", "数据库", "分析读取失败",
                "{\"success\":true,\"alerts\":[{\"alert_name\":\"Page failure\",\"service\":\"postgres-main\",\"duration\":\"16m\",\"current_value\":\"12/5m\"}]}",
                "{\"logs\":[{\"service\":\"postgres-main\",\"message\":\"could not read block in file \\\"base/16401/33909\\\": 12 errors\"}]}",
                "2026-09-14T01:00:00Z");
    }
    static EvidenceDocument doc() {
        return KeywordSearchService.document("postgres.md", "检查", 0,
                "Page read failures can indicate data corruption. Check checksums with SHOW data_checksums; before recovery.",
                "keyword", null, null, 2.0);
    }
    static GroundedAnalysis analysis() {
        return new GroundedAnalysis(true,"postgres-main",List.of(new GroundedAnalysis.Finding(
                "观察到数据页读取失败，上游原因待验证","hypothesis",List.of(
                new GroundedAnalysis.Citation("L1","could not read block in file \"base/16401/33909\": 12 errors"),
                new GroundedAnalysis.Citation(doc().id(),"Page read failures can indicate data corruption.")))),
                List.of(),List.of(new GroundedAnalysis.Action("检查数据校验开关","SHOW data_checksums;","",
                List.of(new GroundedAnalysis.Citation(doc().id(),"Check checksums with SHOW data_checksums; before recovery.")))),List.of("磁盘健康状态"));
    }
    private KnowledgeFiles files() { var c=new FileUploadConfig();c.setPath(root.toString());return new KnowledgeFiles(c); }
    private KeywordSearchService keywords() {
        var chunks=new DocumentChunkService();ReflectionTestUtils.setField(chunks,"chunkConfig",new DocumentChunkConfig());
        return new KeywordSearchService(files(),chunks);
    }
    @Test void originalLogQuotesAndCommandsAreValidatedWithoutJsonEscapeArtifacts() {
        var reports=new DiagnosticReportService();var incident=snapshot();
        var result=reports.parseAndValidate(reports.encode(analysis()),incident,List.of(doc()));
        assertThat(result.relevant()).isTrue();assertThat(result.actions()).hasSize(1);
        assertThat(reports.render(incident,result,List.of(doc()))).contains("16m","12/5m","日志1","文档1","SHOW data_checksums;")
                .doesNotContain(doc().id(),"## 活跃告警");
    }
    @Test void markdownAutolinkBracketsDoNotInvalidateAnOtherwiseExactCitation() {
        var reports=new DiagnosticReportService();var incident=snapshot();
        var source=KeywordSearchService.document("sidekiq.md","检查",0,
                "* Check <https://sentry.example> for errors\n  * Search for `ReactiveCachingWorker` and inspect recent errors",
                "keyword",null,null,1.0);
        var action=new GroundedAnalysis.Action("检查 Sentry 中 ReactiveCachingWorker 的近期错误","","",List.of(
                new GroundedAnalysis.Citation(source.id(),"Check https://sentry.example for errors\nSearch for `ReactiveCachingWorker` and inspect recent errors")));
        var raw=new GroundedAnalysis(true,incident.service(),List.of(),List.of(),List.of(action),List.of());
        assertThat(reports.validate(reports.encode(raw),incident,List.of(source)).analysis().actions()).containsExactly(action);
    }
    @Test void wrongSourcesServicesAndUnsupportedCommandsCannotBePublished() {
        var reports=new DiagnosticReportService();String valid=reports.encode(analysis());
        assertThat(reports.parseAndValidate(valid.replace("postgres-main","another-service"),snapshot(),List.of(doc())).relevant()).isFalse();
        assertThat(reports.parseAndValidate(valid.replace(doc().id(),"D-missing"),snapshot(),List.of(doc())).relevant()).isFalse();
        var unsupported=analysis();
        unsupported=new GroundedAnalysis(true,unsupported.service(),unsupported.findings(),List.of(),
                List.of(new GroundedAnalysis.Action("清理数据库","DROP TABLE customer;","",unsupported.actions().get(0).citations())),List.of());
        var result=reports.parseAndValidate(reports.encode(unsupported),snapshot(),List.of(doc()));
        assertThat(result.actions()).isEmpty();assertThat(result.missingEvidence()).isNotEmpty();
    }
    @Test void numericClaimsCannotBorrowNumbersOutsideTheirQuotedEvidence() {
        var a=analysis();var f=a.findings().get(0);
        var invalid=new GroundedAnalysis(true,a.service(),List.of(new GroundedAnalysis.Finding("出现120次失败","confirmed",f.citations())),List.of(),List.of(),List.of());
        var reports=new DiagnosticReportService();
        assertThat(reports.parseAndValidate(reports.encode(invalid),snapshot(),List.of(doc())).relevant()).isFalse();
    }
    @Test void descriptiveInlineCodeDoesNotEraseOtherwiseGroundedFindings() {
        var a=analysis();var f=a.findings().get(0);
        var formatted=new GroundedAnalysis(true,a.service(),List.of(new GroundedAnalysis.Finding(
                "观察到 `could not read block`，上游原因待验证",f.certainty(),f.citations())),List.of(),a.actions(),List.of());
        var reports=new DiagnosticReportService();
        var result=reports.parseAndValidate(reports.encode(formatted),snapshot(),List.of(doc()));
        assertThat(result.relevant()).isTrue();
        assertThat(result.findings().get(0).text()).doesNotContain("`");
    }
    @Test void evidenceIdsAndAnExactIpAddressAreNotMisreadAsNewMetrics() {
        String log="EHOSTUNREACH failed to connect to 192.0.2.188:8089";
        var incident=new IncidentSnapshot("ip-case","s1","network","读取失败",
                "{\"alerts\":[{\"service\":\"sidekiq\"}]}","{\"logs\":[{\"message\":\""+log+"\"}]}","now");
        var d=KeywordSearchService.document("network.md","诊断",0,log,"keyword",null,null,1.0);
        var f=new GroundedAnalysis.Finding("日志L1与文档"+d.id()+"均记录192.0.2.188:8089连接失败", "confirmed",
                List.of(new GroundedAnalysis.Citation("L1",log),new GroundedAnalysis.Citation(d.id(),log)));
        var raw=new GroundedAnalysis(true,"sidekiq",List.of(f),List.of(),List.of(),List.of());
        var reports=new DiagnosticReportService();
        assertThat(reports.parseAndValidate(reports.encode(raw),incident,List.of(d)).findings()).hasSize(1);
    }
    @Test void auditCannotSelectInventedItemsOrSmuggleNewFindings() {
        var reports=new DiagnosticReportService();var input=reports.auditInput(List.of(analysis()));
        assertThat(reports.validateSelection("{\"findings\":[99]}",snapshot(),input).needsReview()).isTrue();
        assertThat(reports.validateSelection("{\"findings\":[0.5]}",snapshot(),input).needsReview()).isTrue();
        assertThat(reports.validateSelection(reports.encode(analysis()),snapshot(),input).needsReview()).isTrue();
        var rejected=reports.validateSelection("{\"findings\":[],\"missingEvidence\":[\"缺少直接证据\"]}",snapshot(),input);
        assertThat(rejected.needsReview()).isFalse();
        assertThat(rejected.analysis().relevant()).isFalse();
    }
    @Test void anExplicitAuditRejectionMustNotRestoreActions() {
        var reports=new DiagnosticReportService();
        var result=reports.validateSelection("{\"findings\":[0],\"actions\":[]}",snapshot(),reports.auditInput(List.of(analysis())));
        assertThat(result.analysis().relevant()).isTrue();
        assertThat(result.analysis().actions()).isEmpty();
    }
    @Test void auditCanRemoveAnUnsupportedClauseWithoutRegeneratingEvidence() {
        var reports=new DiagnosticReportService();var a=analysis();
        var misleading=new GroundedAnalysis.Finding("出现读取失败，表明配置已开启","hypothesis",a.findings().get(0).citations());
        var input=reports.auditInput(List.of(new GroundedAnalysis(true,a.service(),List.of(misleading),List.of(),a.actions(),List.of())));
        var result=reports.validateSelection("{\"findings\":[{\"index\":0,\"text\":\"出现读取失败，具体原因待验证\",\"certainty\":\"hypothesis\"}],\"actions\":[0]}",snapshot(),input);
        assertThat(result.needsReview()).isFalse();assertThat(result.analysis().relevant()).isTrue();
        assertThat(result.analysis().findings().get(0).text()).doesNotContain("配置已开启");
        assertThat(result.analysis().findings().get(0).citations()).isEqualTo(misleading.citations());
        assertThat(result.analysis().actions()).containsExactlyElementsOf(a.actions());
    }
    @Test void auditCorrectionsCannotInventMeasurementsOrChangeSources() {
        var reports=new DiagnosticReportService();var input=reports.auditInput(List.of(analysis()));
        assertThat(reports.validateSelection("{\"findings\":[{\"index\":0,\"text\":\"发生120次失败\",\"certainty\":\"observation\"}]}",snapshot(),input).needsReview()).isTrue();
        assertThat(reports.validateSelection("{\"findings\":[{\"index\":0,\"text\":\"读取失败\",\"certainty\":\"observation\",\"citations\":[{\"id\":\"D-other\",\"quote\":\"Invented source text\"}]}]}",snapshot(),input).needsReview()).isTrue();
    }
    @Test void auditCanCiteAnotherActualObservationByIdWithoutInventingItsQuote() {
        var reports=new DiagnosticReportService();var incident=snapshot();var input=reports.auditInput(List.of(analysis()));
        var result=reports.validateSelection("{\"findings\":[{\"index\":0,\"text\":\"告警持续16m，记录12次读取失败\",\"certainty\":\"observation\",\"observations\":[\"A1\",\"L1\"]}]}",incident,input);
        assertThat(result.needsReview()).isFalse();assertThat(result.analysis().relevant()).isTrue();
        assertThat(result.analysis().findings().get(0).citations()).contains(new GroundedAnalysis.Citation("A1",incident.observations().get("A1")));
        assertThat(reports.validateSelection("{\"findings\":[{\"index\":0,\"text\":\"读取失败\",\"certainty\":\"observation\",\"observations\":[\"L999\"]}]}",incident,input).needsReview()).isTrue();
    }
    @Test void auditDataProvidesUnambiguousIndexesForEveryList() {
        var reports=new DiagnosticReportService();
        var data=reports.auditData(reports.auditInput(List.of(analysis())));
        assertThat(reports.encode(data)).contains("\"index\":0");
    }
    @Test void aKnownDocumentIdInTheAuditEvidenceListDoesNotInvalidateTheReport() {
        var reports=new DiagnosticReportService();var input=reports.auditInput(List.of(analysis()));
        String response=reports.encode(Map.of("findings",List.of(Map.of("index",0,"text","读取失败，数据损坏为待验证方向",
                "certainty","hypothesis","observations",List.of("L1",doc().id())))));
        var result=reports.validateSelection(response,snapshot(),input);
        assertThat(result.needsReview()).isFalse();assertThat(result.analysis().relevant()).isTrue();
        assertThat(result.analysis().findings().get(0).citations()).extracting(GroundedAnalysis.Citation::id).contains("L1",doc().id());
    }
    @Test void directObservationAndSourcedChecksDoNotRequireAnEstablishedCause() {
        var reports=new DiagnosticReportService();var a=analysis();
        var observation=new GroundedAnalysis.Finding("记录12次读取失败","observation",List.of(a.findings().get(0).citations().get(0)));
        var result=reports.validate(reports.encode(new GroundedAnalysis(true,a.service(),List.of(observation),List.of(),a.actions(),List.of())),snapshot(),List.of(doc()));
        assertThat(result.needsReview()).isFalse();assertThat(result.analysis().relevant()).isTrue();
        assertThat(result.analysis().findings()).containsExactly(observation);
        assertThat(result.analysis().actions()).containsExactlyElementsOf(a.actions());
        assertThat(reports.render(snapshot(),result.analysis(),List.of(doc()))).contains("已观察到","建议操作","SHOW data_checksums;").doesNotContain("候选原因");
        var input=reports.auditInput(List.of(result.analysis()),List.of(doc()));
        String selection=reports.encode(Map.of("findings",List.of(Map.of("index",0,"text","观察到读取失败，与文档所述数据损坏现象相符",
                "certainty","hypothesis","evidence",List.of("L1",doc().id()))),"actions",List.of(0)));
        var audited=reports.validateSelection(selection,snapshot(),input);
        assertThat(audited.needsReview()).isFalse();assertThat(audited.analysis().relevant()).isTrue();
        assertThat(reports.validateSelection(selection.replace(doc().id(),"D-invented"),snapshot(),input).needsReview()).isTrue();
    }
    @Test void anAuditCorrectionCannotTurnALogOnlyObservationIntoACause() {
        var reports=new DiagnosticReportService();var a=analysis();
        var observation=new GroundedAnalysis.Finding("读取失败","observation",List.of(a.findings().get(0).citations().get(0)));
        var input=reports.auditInput(List.of(new GroundedAnalysis(true,a.service(),List.of(a.findings().get(0),observation),List.of(),List.of(),List.of())));
        var result=reports.validateSelection("{\"findings\":[0,{\"index\":1,\"text\":\"可能为磁盘损坏\",\"certainty\":\"hypothesis\"}]}",snapshot(),input);
        assertThat(result.analysis().findings()).containsExactly(a.findings().get(0));
        assertThat(result.rejectedItems()).isEqualTo(1);
        assertThat(result.analysis().findings()).noneMatch(f -> f.text().contains("磁盘损坏"));
    }
    @Test void semanticAuditMayKeepObservationAndChecksWithoutAnyCause() {
        var reports = new DiagnosticReportService(); var original = analysis();
        var observation = new GroundedAnalysis.Finding("记录12次读取失败", "observation",
                List.of(original.findings().get(0).citations().get(0)));
        var draft = new GroundedAnalysis(true, original.service(), List.of(observation), List.of(), original.actions(), List.of());
        var checked = reports.validateSelection("{\"findings\":[0],\"actions\":[0]}", snapshot(), reports.auditInput(List.of(draft), List.of(doc())));
        assertThat(checked.needsReview()).isFalse();
        assertThat(checked.analysis().findings()).containsExactly(observation);
        assertThat(checked.analysis().actions()).containsExactlyElementsOf(original.actions());
    }
    @Test void invalidFindingDoesNotDiscardAuditedSourcedCheck() {
        var reports = new DiagnosticReportService();
        var checked = reports.validateSelection("{\"findings\":[{\"index\":0,\"text\":\"出现999次失败\",\"certainty\":\"observation\",\"evidence\":[\"L1\"]}],\"actions\":[0]}",
                snapshot(), reports.auditInput(List.of(analysis()), List.of(doc())));
        assertThat(checked.needsReview()).isFalse();
        assertThat(checked.rejectedItems()).isEqualTo(1);
        assertThat(checked.analysis().findings()).isEmpty();
        assertThat(checked.analysis().actions()).hasSize(1);
    }
    @Test void globalModelFlagCannotEraseVerifiableObservation() {
        var reports = new DiagnosticReportService(); var original = analysis();
        var observation = new GroundedAnalysis.Finding("读取失败", "observation", List.of(original.findings().get(0).citations().get(0)));
        var checked = reports.validate(reports.encode(new GroundedAnalysis(false, original.service(), List.of(observation), List.of(), List.of(), List.of())), snapshot(), List.of());
        assertThat(checked.analysis().relevant()).isTrue();
        assertThat(checked.analysis().findings()).containsExactly(observation);
        assertThat(checked.analysis().actions()).isEmpty();
        assertThat(checked.needsReview()).isFalse();
    }
    @Test void markdownOnlyQuotesCannotCountAsDocumentEvidence() {
        var reports=new DiagnosticReportService();var a=analysis();var f=a.findings().get(0);
        var bad=new GroundedAnalysis.Finding(f.text(),f.certainty(),List.of(f.citations().get(0),new GroundedAnalysis.Citation(doc().id(),"````````")));
        assertThat(reports.validate(reports.encode(new GroundedAnalysis(true,a.service(),List.of(bad),List.of(),List.of(),List.of())),snapshot(),List.of(doc())).analysis().relevant()).isFalse();
    }
    @Test void merelyRetrievingADocumentDoesNotSupportAnUncitedCause() {
        var f=analysis().findings().get(0);
        var unsupported=new GroundedAnalysis(true,"postgres-main",List.of(new GroundedAnalysis.Finding(
                "磁盘损坏导致读取失败","hypothesis",List.of(f.citations().get(0)))),List.of(),List.of(),List.of());
        var reports=new DiagnosticReportService();
        assertThat(reports.validate(reports.encode(unsupported),snapshot(),List.of(doc())).analysis().relevant()).isFalse();
    }
    @Test void aShortInventedLineCannotBeDiscardedWhileValidatingAQuote() {
        var d=KeywordSearchService.document("runbook.md","检查",0,"Page reads failed.\nNetwork is reachable.","keyword",null,null,1.0);
        var f=new GroundedAnalysis.Finding("观察到读取异常，原因待验证","hypothesis",List.of(
                analysis().findings().get(0).citations().get(0),new GroundedAnalysis.Citation(d.id(),"Page reads failed.\nNOT\nNetwork is reachable.")));
        var reports=new DiagnosticReportService();
        assertThat(reports.validate(reports.encode(new GroundedAnalysis(true,"postgres-main",List.of(f),List.of(),List.of(),List.of())),snapshot(),List.of(d)).analysis().relevant()).isFalse();
    }
    @Test void commandPrerequisitesCannotBeSilentlyRemoved() {
        var a=analysis();
        var action=new GroundedAnalysis.Action("检查数据校验开关","SHOW data_checksums;","未经确认先切换到另一台数据库",a.actions().get(0).citations());
        var reports=new DiagnosticReportService();
        var validated=reports.validate(reports.encode(new GroundedAnalysis(true,a.service(),a.findings(),List.of(),List.of(action),List.of())),snapshot(),List.of(doc()));
        assertThat(validated.analysis().actions()).noneSatisfy(x->assertThat(x.command()).isNotEmpty());
    }
    @Test void markdownPresentationAndSeparatedExactFieldsAreValidEvidence() {
        var d=KeywordSearchService.document("network.md","检查",0,"- **Network Issues:** Connectivity problems could prevent communication.\n- **Service Outage:** The service might be down.","keyword",null,null,1.0);
        var f=new GroundedAnalysis.Finding("观察到读取失败，通信异常是候选方向","hypothesis",List.of(
                new GroundedAnalysis.Citation("A1","service: postgres-main\ncurrent_value: 12/5m"),
                new GroundedAnalysis.Citation(d.id(),"Network Issues: Connectivity problems could prevent communication.")));
        var reports=new DiagnosticReportService();
        assertThat(reports.validate(reports.encode(new GroundedAnalysis(true,"postgres-main",List.of(f),List.of(),List.of(),List.of())),snapshot(),List.of(d)).analysis().relevant()).isTrue();
    }
    @Test void logSeverityAloneCannotSupportAnExclusion() {
        var incident=new IncidentSnapshot("id","s1","database","读取失败","{\"alerts\":[{\"service\":\"postgres-main\"}]}","{\"logs\":[{\"level\":\"ERROR\",\"message\":\"could not read block\"}]}","now");
        var a=analysis();
        var valid=new GroundedAnalysis.Finding("读取失败，原因待验证","hypothesis",List.of(new GroundedAnalysis.Citation("L1","could not read block"),a.findings().get(0).citations().get(1)));
        var falseExclusion=new GroundedAnalysis.Finding("未见PANIC，所以排除磁盘空间问题","observation",List.of(new GroundedAnalysis.Citation("L1","level: ERROR")));
        var reports=new DiagnosticReportService();
        var result=reports.validate(reports.encode(new GroundedAnalysis(true,"postgres-main",List.of(valid),List.of(falseExclusion),List.of(),List.of())),incident,List.of(doc()));
        assertThat(result.analysis().relevant()).isTrue();
        assertThat(result.analysis().contradictions()).isEmpty();
    }
    @Test void keywordToolReadsOriginalContentWithoutVectorDatabaseAndExcludesPrivateFiles() throws Exception {
        Files.writeString(root.resolve("architecture.md"),"# 模型\n\nThis pipeline uses EdgeProbeNet for visual boundaries.");
        Files.writeString(root.resolve("other.md"),"# 监控\n\nPostgreSQL database monitoring.");
        Files.createDirectory(root.resolve(".conversations"));
        Files.writeString(root.resolve(".conversations/private.md"),"EdgeProbeNet secret");
        var hits=keywords().search("请帮我查一下 EdgeProbeNet",3);
        assertThat(hits).isNotEmpty().allSatisfy(h->assertThat(h.sourceFile()).isEqualTo("architecture.md"));
        assertThat(keywords().search("NonexistentComponentXYZ",3)).isEmpty();
    }
    @Test void staleVectorsAreRejectedAndSourceExpansionKeepsWholeSections() throws Exception {
        Files.writeString(root.resolve("postgres.md"),doc().content());
        var hit=new VectorSearchService.SearchResult();hit.setSourceFile("postgres.md");hit.setContent("obsolete advice");
        assertThat(keywords().validateVectors(List.of(hit))).isEmpty();
        hit.setContent(doc().content());
        assertThat(keywords().validateVectors(List.of(hit))).hasSize(1);
        assertThat(keywords().expand(List.of(doc()))).extracting(EvidenceDocument::content).contains(doc().content());
    }
    @Test void retrievalFallsBackAfterVectorOutageAndRewriteRunsOnlyOncePerRequest() throws Exception {
        var pipeline=mock(RetrievalPipelineService.class);var keyword=mock(KeywordSearchService.class);var models=mock(ChatModelFactory.class);
        when(pipeline.retrieve(anyString(),eq(3))).thenThrow(new IllegalStateException("offline"));
        when(keyword.search(anyString(),eq(3))).thenReturn(List.of());
        when(models.rewrite(anyString())).thenReturn("precise-error-code");
        when(keyword.search("precise-error-code",3)).thenReturn(List.of(doc()));
        when(keyword.expand(anyList())).thenAnswer(i->i.getArgument(0));
        var retrieval=new KnowledgeRetrievalService(pipeline,keyword,models,3);var session=new KnowledgeRetrievalService.SearchSession();
        var bundle=retrieval.search("verbose question",session);
        assertThat(bundle.documents()).containsExactly(doc());assertThat(bundle.stage()).isEqualTo(2);
        assertThat(bundle.notices()).contains("向量检索不可用");
        assertThat(retrieval.search("verbose question",session)).isSameAs(bundle);
        assertThat(retrieval.search("unrelated second query",session,2).documents()).isEmpty();
        verify(models,times(1)).rewrite(anyString());
        var ordered=inOrder(pipeline,keyword,models);
        ordered.verify(pipeline).retrieve("verbose question",3);
        ordered.verify(keyword).search("verbose question",3);
        ordered.verify(models).rewrite(anyString());
        ordered.verify(keyword).search("precise-error-code",3);
    }
    @Test void conversationRestoresSameIncidentAndEvidenceAcrossRestartAndRejectsForeignSession() throws Exception {
        var store=new ConversationStore(files());var incident=snapshot();
        var outcome=new AiOpsService.DiagnosisOutcome(incident,"completed","完成","报告",analysis(),List.of(doc()));
        store.save("first-session",outcome);store.append("first-session","怎么处理","检查校验开关");
        var restarted=new ConversationStore(files());
        assertThat(restarted.diagnosis("first-session",null)).isEqualTo(outcome);
        assertThat(restarted.history("first-session")).hasSize(2);
        assertThatThrownBy(()->restarted.diagnosis("different-session",incident.id())).hasMessageContaining("404");
        restarted.clear("first-session");
        assertThat(restarted.diagnosis("first-session",null)).isNull();
        assertThat(restarted.history("first-session")).isEmpty();
    }
}
