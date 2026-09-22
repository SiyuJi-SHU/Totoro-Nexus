package org.example.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.service.ChatModelFactory;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EvaluationLifecycleTest {
    final ObjectMapper json=new ObjectMapper();
    AgentRuntime.Result greeting(){return new AgentRuntime.Result("completed","hello",new AgentAnswerService.Answer(List.of(),List.of(),List.of()),null,List.of(),List.of(),List.of(),0,1,"mock","chat_reply","GREETING","react");}
    String generatedRows(int... ids){var rows=json.createArrayNode();for(int id:ids)rows.addObject().put("targetChunkId","chunk-"+id).put("question","问题 "+id).put("referenceAnswer","答案 "+id);return rows.toString();}

    @Test void multidimensionalReviewCannotHideFailureOrSkipRequiredJudgments()throws Exception {
        String raw="""
          {"passed":true,"checks":{
          "answerCorrectness":{"status":"passed","reason":"answers the question"},
          "citationSupport":{"status":"not_applicable","reason":"greeting"},
          "observationDiscipline":{"status":"not_applicable","reason":"no diagnosis"},
          "actionSupport":{"status":"failed","reason":"invented command"}}}
          """;
        assertThat(PlatformEvaluation.checkedReview(raw,json,greeting()).path("passed").asBoolean()).isFalse();
        assertThatThrownBy(()->PlatformEvaluation.checkedReview("{\"passed\":true,\"reason\":\"fine\"}",json,greeting())).isInstanceOf(AnswerFormatException.class);
        assertThatThrownBy(()->PlatformEvaluation.checkedReview(raw.replace("\"status\":\"passed\"","\"status\":\"not_applicable\""),json,greeting())).isInstanceOf(AnswerFormatException.class);
    }

    @Test void generatedQuestionDeduplicationIgnoresPunctuationAndMinorRephrasing() {
        assertThat(PlatformEvaluation.similarQuestion("如何排查 Redis 连接超时？","如何排查Redis连接超时")).isTrue();
        assertThat(PlatformEvaluation.similarQuestion("如何排查 Redis 连接超时？","PostgreSQL 死锁如何处理？")).isFalse();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void realJobSeparatesReviewerFailureFromExecutionFailureAndPinsMultiTurnSessions(boolean selectPro)throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__platform_catalog.sql"),new ClassPathResource("db/migration/V5__session_origin.sql"),new ClassPathResource("db/migration/V6__agent_drafts.sql"),new ClassPathResource("db/migration/V7__evaluation_suite_targets.sql")).execute(ds);
        var db=new JdbcTemplate(ds);
        // Use the production ledger table definition; H2 does not support V2's later partial index.
        db.execute(new ClassPathResource("db/migration/V2__runtime_usage.sql").getContentAsString(java.nio.charset.StandardCharsets.UTF_8).split("CREATE INDEX")[0]);
        var catalog=new PlatformCatalog(db,json);catalog.initializeDefaults();var runs=new AgentRunStore(db,catalog);
        if(selectPro){var config=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(catalog.agent("oncall",null).config());config.put("chatModel","deepseek-v4-pro");catalog.saveAgent("oncall",json.treeToValue(config,PlatformModels.AgentConfig.class));}
        int agentVersion=catalog.agent("oncall",null).version();
        var runtime=mock(AgentRuntime.class);var search=mock(KnowledgeSearch.class);var models=mock(ChatModelFactory.class);
        when(models.modelName()).thenReturn("mock");when(search.scope(anyList())).thenReturn(new KnowledgeSearch.Scope(List.of("existing-knowledge"),Set.of(),List.of()));
        when(models.call(any(),eq("task-evaluation"),anyString(),anyString())).thenThrow(new IllegalStateException("reviewer offline"));
        List<AgentRuntime.Input> inputs=new ArrayList<>();
        when(runtime.start(anyString(),any(),any(),nullable(org.example.dto.IncidentSnapshot.class))).thenAnswer(inv->{
            AgentRuntime.Input input=inv.getArgument(1);inputs.add(input);
            if(input.question().equals("execution failure"))throw new IllegalStateException("runtime offline");
            var session=runs.session(input.sessionId(),inv.getArgument(0));var run=runs.create(session,input,Map.of());
            runs.finish(run.id(),"completed",greeting(),null);return runs.get(run.id());
        });
        var evaluation=new PlatformEvaluation(db,catalog,json,search,runtime,runs,models);
        try {
            var suite=evaluation.importSuite("lifecycle","agent",json.readTree("""
              [{"id":"conversation","turns":[{"question":"hello","expectedKind":"chat_reply"},{"question":"hello again","expectedKind":"chat_reply"}]},
               {"id":"ungraded","question":"review failure","referenceAnswer":"hello"},
               {"id":"error","question":"execution failure","expectedKind":"chat_reply"}]
              """));
            var job=evaluation.start("alice",suite.id(),new PlatformEvaluation.Config(List.of(),"semantic",10,3,true,"oncall",agentVersion));
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while(Set.of("queued","running").contains(job.status())&&System.nanoTime()<deadline){Thread.sleep(10);job=evaluation.job(job.id());}
            assertThat(job.status()).isEqualTo("partial");var result=json.valueToTree(job.result());var summary=result.path("summary");
            assertThat(result.path("model").asText()).isEqualTo(selectPro?"deepseek-v4-pro":"mock");
            assertThat(result.path("judgeModel").asText()).isEqualTo("mock");
            assertThat(summary.path("passedCases").asInt()).isEqualTo(1);
            assertThat(summary.path("gradedCases").asInt()).isEqualTo(1);
            assertThat(summary.path("ungradedCases").asInt()).isEqualTo(1);
            assertThat(summary.path("groundingUnreviewedCases").asInt()).isEqualTo(1);
            assertThat(summary.path("groundednessCases").asInt()).isZero();
            assertThat(summary.path("groundednessPassRate").isNull()).isTrue();
            assertThat(summary.path("errors").asInt()).isEqualTo(1);
            assertThat(result.path("results").get(1).path("gradeStatus").asText()).isEqualTo("ungraded");
            assertThat(result.path("results").get(1).path("turns").get(0).path("passed").isNull()).isTrue();
            assertThat(inputs).hasSize(4);assertThat(inputs.get(0).sessionId()).isEqualTo(inputs.get(1).sessionId());
            assertThat(inputs.get(2).sessionId()).isNotEqualTo(inputs.get(0).sessionId()).isNotEqualTo(inputs.get(3).sessionId());
            for(var input:inputs){assertThat(runs.origin(input.sessionId())).isEqualTo("evaluation");assertThat(runs.session(input.sessionId(),"alice").agentVersion()).isEqualTo(agentVersion);}
            assertThat(runs.sessions("alice","oncall")).isEmpty();
            assertThat(inputs).allSatisfy(i->assertThat(i.question()).doesNotContain("referenceAnswer"));
            String baseId=job.id();int jobCount=evaluation.jobs().size();
            for(String changed:List.of("sourceHash","caseManifest","sourceVersions","model","judgeModel")) {
                var altered=(com.fasterxml.jackson.databind.node.ObjectNode)result.deepCopy();
                if(changed.equals("caseManifest"))((com.fasterxml.jackson.databind.node.ObjectNode)altered.path(changed).path("cases").get(0)).put("question","changed text, same case ID");
                else if(changed.equals("sourceVersions"))altered.putArray(changed).addObject().put("version","changed-source");
                else altered.put(changed,"changed-version");
                db.update("UPDATE evaluation_jobs SET result_json=? WHERE id=?",catalog.encode(altered),baseId);
                assertThatThrownBy(()->evaluation.retryIncomplete("alice",baseId)).hasMessageContaining("请完整重跑评测");
                assertThat(evaluation.jobs()).hasSize(jobCount);
            }
            db.update("UPDATE evaluation_jobs SET result_json=? WHERE id=?",catalog.encode(result),baseId);
            var retry=evaluation.retryIncomplete("alice",baseId);
            deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while(System.nanoTime()<deadline) {
                retry=evaluation.job(retry.id());
                if(json.valueToTree(retry.result()).has("retryOf"))break;
                Thread.sleep(10);
            }
            var merged=json.valueToTree(retry.result());
            assertThat(merged.path("retryOf").asText()).isEqualTo(baseId);
            assertThat(merged.path("results")).hasSize(3);
            assertThat(merged.path("caseManifest")).isEqualTo(result.path("caseManifest"));
            assertThat(merged.path("sourceHash").asText()).isEqualTo(evaluation.sourceHash());
        }finally{evaluation.close();}
    }

    @Test void workspaceArchivesUnboundLegacyScenarioCases()throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__platform_catalog.sql"),new ClassPathResource("db/migration/V5__session_origin.sql"),new ClassPathResource("db/migration/V6__agent_drafts.sql"),new ClassPathResource("db/migration/V7__evaluation_suite_targets.sql")).execute(ds);
        var db=new JdbcTemplate(ds);var catalog=new PlatformCatalog(db,json);catalog.initializeDefaults();
        var search=mock(KnowledgeSearch.class);when(search.scope(List.of("existing-knowledge"))).thenReturn(new KnowledgeSearch.Scope(List.of("existing-knowledge"),Set.of(),List.of()));
        var models=mock(ChatModelFactory.class);var evaluation=new PlatformEvaluation(db,catalog,json,search,mock(AgentRuntime.class),new AgentRunStore(db,catalog),models);
        try {
            evaluation.importSuite("12 个告警场景","agent",json.readTree("""
                [{"id":"alert-1","question":"检查告警","scenarioId":"apdex_slo_violation_001","expectedKind":"incident_report"}]
                """));
            var workspace=evaluation.workspaces().stream().filter(w->w.knowledgeBase().id().equals("existing-knowledge")).findFirst().orElseThrow();
            assertThat(workspace.retrieval().state()).isEqualTo("unconfigured");
            assertThat(workspace.agents()).singleElement().satisfies(agent->{
                assertThat(agent.strategy()).isEqualTo("workflow");
                assertThat(agent.caseCount()).isZero();
                assertThat(agent.state()).isEqualTo("unconfigured");
            });
            assertThat(workspace.archivedSetCount()).isEqualTo(1);
        }finally{evaluation.close();}
    }

    @Test void workspaceKeepsCurrentBaselineWhenANewerComparisonUsesDifferentRetrievalConfig()throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__platform_catalog.sql"),new ClassPathResource("db/migration/V5__session_origin.sql"),new ClassPathResource("db/migration/V6__agent_drafts.sql"),new ClassPathResource("db/migration/V7__evaluation_suite_targets.sql")).execute(ds);
        var db=new JdbcTemplate(ds);var catalog=new PlatformCatalog(db,json);catalog.initializeDefaults();
        var search=mock(KnowledgeSearch.class);when(search.scope(List.of("existing-knowledge"))).thenReturn(new KnowledgeSearch.Scope(List.of("existing-knowledge"),Set.of(),List.of()));
        var models=mock(ChatModelFactory.class);when(models.modelName()).thenReturn("mock");
        var evaluation=new PlatformEvaluation(db,catalog,json,search,mock(AgentRuntime.class),new AgentRunStore(db,catalog),models);
        try {
            var suite=evaluation.importSuite("Current retrieval","retrieval",json.readTree("""
                [{"id":"r1","question":"如何排查？","expectedChunkIds":["chunk-1"],"knowledgeBaseId":"existing-knowledge"}]
                """),"existing-knowledge","","");
            var semantic=new PlatformEvaluation.Config(List.of("existing-knowledge"),"semantic",20,10,true,null,null);
            var comparison=new PlatformEvaluation.Config(List.of("existing-knowledge"),"hybrid",20,10,true,null,null);
            var result=Map.of("sourceVersions",List.of(),"caseManifest",suite,"model","mock","pipelineVersion","rag-v2","sourceHash",evaluation.sourceHash(),"summary",Map.of());
            db.update("INSERT INTO evaluation_jobs(id,set_id,config_json,status,result_json) VALUES(?,?,?,'completed',?)","baseline",suite.id(),catalog.encode(semantic),catalog.encode(result));
            Thread.sleep(10);
            db.update("INSERT INTO evaluation_jobs(id,set_id,config_json,status,result_json) VALUES(?,?,?,'completed',?)","comparison",suite.id(),catalog.encode(comparison),catalog.encode(result));

            var retrieval=evaluation.workspaces().stream().filter(w->w.knowledgeBase().id().equals("existing-knowledge")).findFirst().orElseThrow().retrieval();
            assertThat(retrieval.state()).isEqualTo("current");
            assertThat(retrieval.latestJob().id()).isEqualTo("baseline");
            var oldBuild=json.valueToTree(result);((com.fasterxml.jackson.databind.node.ObjectNode)oldBuild).put("sourceHash","older-build");
            db.update("UPDATE evaluation_jobs SET result_json=?",catalog.encode(oldBuild));
            assertThat(evaluation.workspaces().stream().filter(w->w.knowledgeBase().id().equals("existing-knowledge")).findFirst().orElseThrow().retrieval().state()).isEqualTo("stale");
        }finally{evaluation.close();}
    }

    @Test void currentAgentCasesAreDerivedOrFrozenWithoutAnotherLlmGeneration()throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__platform_catalog.sql"),new ClassPathResource("db/migration/V5__session_origin.sql"),new ClassPathResource("db/migration/V6__agent_drafts.sql"),new ClassPathResource("db/migration/V7__evaluation_suite_targets.sql")).execute(ds);
        var db=new JdbcTemplate(ds);var catalog=new PlatformCatalog(db,json);catalog.initializeDefaults();
        var knowledge=catalog.saveAgent(null,new PlatformModels.AgentConfig("Knowledge Agent","","task","",List.of("existing-knowledge"),List.of("knowledge-tools"),"react",8,120,.1,10,3,2000));
        var workflow=catalog.agents().stream().filter(a->a.config().strategy().equals("workflow")).findFirst().orElseThrow();
        var search=mock(KnowledgeSearch.class);when(search.scope(List.of("existing-knowledge"))).thenReturn(new KnowledgeSearch.Scope(List.of("existing-knowledge"),Set.of(),List.of()));
        var models=mock(ChatModelFactory.class);
        var evaluation=new PlatformEvaluation(db,catalog,json,search,mock(AgentRuntime.class),new AgentRunStore(db,catalog),models);
        try {
            evaluation.importSuite("Retrieval","retrieval",json.readTree("""
                [{"id":"r1","question":"如何排查？","expectedChunkIds":["chunk-1"],"expectedSources":["runbook.md"],"requiredEvidence":["原文证据"],"referenceAnswer":"按原文排查","knowledgeBaseId":"existing-knowledge"}]
                """),"existing-knowledge","","");
            var derived=evaluation.deriveKnowledgeAgentSuite("existing-knowledge",knowledge.id());
            assertThat(derived.targetAgentId()).isEqualTo(knowledge.id());
            assertThat(derived.cases()).singleElement().satisfies(c->{
                assertThat(c.origin()).isEqualTo("derived_retrieval");assertThat(c.expectedKind()).isEqualTo("knowledge_answer");assertThat(c.requiredTools()).contains("knowledge.search");
            });
            var frozen=evaluation.createWorkflowSuite("existing-knowledge",workflow.id());
            assertThat(frozen.cases()).hasSize(12).allSatisfy(c->{
                assertThat(c.origin()).isEqualTo("frozen_scenario");assertThat(c.incidentSnapshot()).isNotNull();assertThat(c.incidentSnapshot().logs()).isNotEmpty();assertThat(c.requiredTools()).contains("incident.read");
            });
            verifyNoInteractions(models);
        }finally{evaluation.close();}
    }

    @Test void generatedRetrievalCaseCanBeSavedWithSerializedEmptyTurns()throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__platform_catalog.sql"),new ClassPathResource("db/migration/V5__session_origin.sql"),new ClassPathResource("db/migration/V6__agent_drafts.sql"),new ClassPathResource("db/migration/V7__evaluation_suite_targets.sql")).execute(ds);
        var db=new JdbcTemplate(ds);var catalog=new PlatformCatalog(db,json);catalog.initializeDefaults();
        var evaluation=new PlatformEvaluation(db,catalog,json,mock(KnowledgeSearch.class),mock(AgentRuntime.class),new AgentRunStore(db,catalog),mock(ChatModelFactory.class));
        try {
            var generated=new PlatformEvaluation.Case("synthetic-1","Runner 为什么一直 pending？",List.of("chunk-1"),List.of("runner.md"),List.of("检查标签"),List.of(),List.of(),"","","检查 Runner 标签","",false,"",json.nullNode(),"synthetic","confirmed","existing-knowledge",List.of());
            var suite=evaluation.importSuite("生成召回 Case","retrieval",json.valueToTree(List.of(generated)));
            assertThat(suite.cases()).singleElement().satisfies(saved->{
                assertThat(saved.expectedChunkIds()).containsExactly("chunk-1");
                assertThat(saved.turns()).isEmpty();
            });
        }finally{evaluation.close();}
    }

    @Test void retrievalCasesCanBeBatchAppendedAndFullyDeleted()throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__platform_catalog.sql"),new ClassPathResource("db/migration/V5__session_origin.sql"),new ClassPathResource("db/migration/V6__agent_drafts.sql"),new ClassPathResource("db/migration/V7__evaluation_suite_targets.sql")).execute(ds);
        var db=new JdbcTemplate(ds);var catalog=new PlatformCatalog(db,json);catalog.initializeDefaults();
        var search=mock(KnowledgeSearch.class);when(search.scope(List.of("existing-knowledge"))).thenReturn(new KnowledgeSearch.Scope(List.of("existing-knowledge"),Set.of(),List.of()));
        var evaluation=new PlatformEvaluation(db,catalog,json,search,mock(AgentRuntime.class),new AgentRunStore(db,catalog),mock(ChatModelFactory.class));
        try {
            var suite=evaluation.importSuite("召回基线","retrieval",json.readTree("""
                [{"id":"case-1","question":"问题1","expectedChunkIds":["chunk-1"],"knowledgeBaseId":"existing-knowledge"}]
                """));
            var appended=evaluation.appendCase(suite.id(),json.readTree("""
                [{"id":"case-2","question":"问题2","expectedChunkIds":["chunk-2"],"knowledgeBaseId":"existing-knowledge"},
                 {"id":"case-3","question":"问题3","expectedChunkIds":["chunk-3"],"knowledgeBaseId":"existing-knowledge"}]
                """));
            assertThat(appended.cases()).extracting(PlatformEvaluation.Case::id).containsExactly("case-1","case-2","case-3");
            assertThat(evaluation.deleteCase(suite.id(),"case-2").cases()).extracting(PlatformEvaluation.Case::id).containsExactly("case-1","case-3");
            assertThat(evaluation.deleteCase(suite.id(),"case-1").cases()).extracting(PlatformEvaluation.Case::id).containsExactly("case-3");
            assertThat(evaluation.deleteCase(suite.id(),"case-3").cases()).isEmpty();
            var workspace=evaluation.workspaces().stream().filter(w->w.knowledgeBase().id().equals("existing-knowledge")).findFirst().orElseThrow();
            assertThat(workspace.retrieval().state()).isEqualTo("unconfigured");
            assertThat(workspace.retrieval().setId()).isNull();
        }finally{evaluation.close();}
    }

    @Test void retrievalGenerationRetriesMissingRowsAndHonorsRequestedCount()throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__platform_catalog.sql")).execute(ds);
        var db=new JdbcTemplate(ds);var catalog=new PlatformCatalog(db,json);var search=mock(KnowledgeSearch.class);var models=mock(ChatModelFactory.class);
        var scope=new KnowledgeSearch.Scope(List.of("existing-knowledge"),Set.of(),List.of());
        var chunks=java.util.stream.IntStream.rangeClosed(1,10).mapToObj(i->{String content="Evidence for chunk "+i+". "+"Supporting detail. ".repeat(6);return new PlatformChunk("chunk-"+i,"doc-"+i,"v1","existing-data","doc-"+i+".md","Section",0,0,content.length(),content,null,null,null,"source");}).toList();
        when(search.scope(List.of("existing-knowledge"))).thenReturn(scope);when(search.evaluationChunks(scope)).thenReturn(chunks);
        var generationCalls=new java.util.concurrent.atomic.AtomicInteger();
        when(models.call(any(),eq("eval-case-generation"),anyString(),anyString())).thenAnswer(inv->{
            var inputs=json.readTree((String)inv.getArgument(3)).path("chunks");var rows=json.createArrayNode();int n=0;
            boolean partial=generationCalls.incrementAndGet()==2;
            for(var input:inputs){if(partial&&n++>0)break;var row=rows.addObject();row.put("targetChunkId",input.path("chunkId").asText());row.put("question",input.path("chunkId").asText());row.put("referenceAnswer",input.path("content").asText());row.putArray("requiredEvidence").add(input.path("content").asText());}return rows.toString();
        });
        when(models.call(any(),eq("eval-label-grounding"),anyString(),anyString())).thenAnswer(inv->{var rows=json.createArrayNode();for(var input:json.readTree((String)inv.getArgument(3)))rows.addObject().put("id",input.path("id").asText()).put("supported",true);return rows.toString();});
        var evaluation=new PlatformEvaluation(db,catalog,json,search,mock(AgentRuntime.class),new AgentRunStore(db,catalog),models);
        try {
            var cases=evaluation.generateRetrievalCases("existing-knowledge",10,"zh");
            assertThat(cases).hasSize(10);
            assertThat(cases).extracting(c->c.expectedChunkIds().get(0)).containsExactlyInAnyOrder("chunk-1","chunk-2","chunk-3","chunk-4","chunk-5","chunk-6","chunk-7","chunk-8","chunk-9","chunk-10");
            assertThat(cases).allSatisfy(c->assertThat(c.requiredEvidence()).singleElement().asString().startsWith("Evidence for chunk"));
            verify(models,times(3)).call(any(),eq("eval-case-generation"),anyString(),anyString());
        }finally{evaluation.close();}
    }

    @Test void agentSuiteFollowsAgentIdentityAndStrategyAcrossVersions()throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__platform_catalog.sql"),new ClassPathResource("db/migration/V5__session_origin.sql"),new ClassPathResource("db/migration/V6__agent_drafts.sql"),new ClassPathResource("db/migration/V7__evaluation_suite_targets.sql")).execute(ds);
        var db=new JdbcTemplate(ds);var catalog=new PlatformCatalog(db,json);catalog.initializeDefaults();
        var config=new PlatformModels.AgentConfig("Target Agent","","task","",List.of("existing-knowledge"),List.of("knowledge-tools"),"react",8,120,.1,10,3,2000);
        var target=catalog.saveAgent(null,config);var other=catalog.saveAgent(null,new PlatformModels.AgentConfig("Other Agent","","task","",List.of("existing-knowledge"),List.of("knowledge-tools"),"react",8,120,.1,10,3,2000));
        var search=mock(KnowledgeSearch.class);when(search.scope(List.of("existing-knowledge"))).thenReturn(new KnowledgeSearch.Scope(List.of("existing-knowledge"),Set.of(),List.of()));
        var evaluation=new PlatformEvaluation(db,catalog,json,search,mock(AgentRuntime.class),new AgentRunStore(db,catalog),mock(ChatModelFactory.class));
        try {
            evaluation.importSuite("Target cases","agent",json.readTree("""
                [{"id":"answer","question":"回答问题","referenceAnswer":"参考答案","knowledgeBaseId":"existing-knowledge"}]
                """),"existing-knowledge",target.id(),"react");
            var workspace=evaluation.workspaces().stream().filter(w->w.knowledgeBase().id().equals("existing-knowledge")).findFirst().orElseThrow();
            assertThat(workspace.agents().stream().filter(a->a.id().equals(target.id())).findFirst().orElseThrow().caseCount()).isEqualTo(1);
            assertThat(workspace.agents().stream().filter(a->a.id().equals(other.id())).findFirst().orElseThrow().state()).isEqualTo("unconfigured");
            catalog.saveAgent(target.id(),new PlatformModels.AgentConfig("Target Agent","","task","",List.of("existing-knowledge"),List.of("knowledge-tools"),"plan_execute_replan",8,120,.1,10,3,2000));
            workspace=evaluation.workspaces().stream().filter(w->w.knowledgeBase().id().equals("existing-knowledge")).findFirst().orElseThrow();
            assertThat(workspace.agents().stream().filter(a->a.id().equals(target.id())).findFirst().orElseThrow().state()).isEqualTo("unconfigured");
        }finally{evaluation.close();}
    }
}
