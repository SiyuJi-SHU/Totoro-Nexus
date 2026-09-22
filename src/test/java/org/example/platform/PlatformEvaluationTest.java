package org.example.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PlatformEvaluationTest {
    private final ObjectMapper json=new ObjectMapper();
    @Test void failedOrMerelyStartedToolsNeverMeetRequirements() throws Exception {
        var ok=json.readTree("{\"id\":\"documents.read\",\"result\":{\"content\":\"actual source\"}}");
        assertFalse(PlatformEvaluation.successfulTool("tool_start",ok));
        assertTrue(PlatformEvaluation.successfulTool("tool_end",ok));
        assertTrue(PlatformEvaluation.successfulTool("tool_end",json.readTree("{\"id\":\"knowledge.list\",\"result\":{\"documents\":[],\"total\":0}}")));
        for(String status:List.of("error","denied","missing_input","budget_exhausted","no_results"))
            assertFalse(PlatformEvaluation.successfulTool("tool_end",json.readTree("{\"result\":{\"status\":\""+status+"\"}}")));
    }
    @Test void knowledgeRetrievalRequirementAcceptsEquivalentSourcedReadingButNotToolFreeAnswers() {
        assertTrue(PlatformEvaluation.toolRequirementsPass(Set.of("knowledge.search"),List.of("knowledge.search")));
        assertTrue(PlatformEvaluation.toolRequirementsPass(Set.of("knowledge.list","documents.read"),List.of("knowledge.search")));
        assertTrue(PlatformEvaluation.toolRequirementsPass(Set.of("documents.search"),List.of("knowledge.search")));
        assertFalse(PlatformEvaluation.toolRequirementsPass(Set.of("knowledge.list"),List.of("knowledge.search")));
        assertFalse(PlatformEvaluation.toolRequirementsPass(Set.of(),List.of("knowledge.search")));
        assertFalse(PlatformEvaluation.toolRequirementsPass(Set.of("documents.read"),List.of("incident.read")));
    }
    @Test void requiredSourcesUseUniqueDenominatorAndToolArgumentsMustMatch() throws Exception {
        assertEquals(.5,PlatformEvaluation.sourceRecall(List.of("a.md","b.md","b.md"),Set.of("a.md","other.md")));
        var required=json.readTree("{\"documentId\":\"a\",\"version\":\"v1\"}");
        assertTrue(PlatformEvaluation.containsParameters(json.readTree("{\"documentId\":\"a\",\"version\":\"v1\",\"offset\":0}"),required));
        assertFalse(PlatformEvaluation.containsParameters(json.readTree("{\"documentId\":\"a\",\"version\":\"v2\"}"),required));
    }
    @Test void oldCasesDeserializeWithoutTurnsOrNewLabels() throws Exception {
        var c=json.readValue("{\"id\":\"old\",\"question\":\"hello\",\"expectedStatus\":\"completed\"}",PlatformEvaluation.Case.class);
        assertTrue(c.turns().isEmpty());assertTrue(c.incidentText().isEmpty());assertTrue(c.expectedSources().isEmpty());assertTrue(c.expectedChunkIds().isEmpty());
        assertEquals("imported",c.origin());assertEquals("confirmed",c.reviewStatus());
    }
    @Test void chunkTargetsAndP95UseDeterministicMetrics() {
        assertEquals(2.0/3,PlatformEvaluation.targetRecall(List.of("c1","c2","c3"),Set.of("c1","c3")),.0001);
        assertEquals(90,PlatformEvaluation.percentile95(List.of(10L,30L,20L,90L,40L)));
    }
    @Test void sectionAliasesKeepOriginalQueryAndAddGenericEnglishLabels() {
        assertEquals("普通问题",KnowledgeSearch.expandSectionAliases("普通问题"));
        assertEquals(23,KnowledgeSearch.parseSectionNumber("二十三"));
        assertEquals(10,KnowledgeSearch.parseSectionNumber("十"));
        assertEquals(105,KnowledgeSearch.parseSectionNumber("一百零五"));
        String expanded=KnowledgeSearch.expandSectionAliases("请查第二十三卷的内容");
        assertTrue(expanded.startsWith("请查第二十三卷的内容"));
        assertTrue(expanded.contains("Book 23")&&expanded.contains("Book XXIII")&&expanded.contains("Chapter 23"));
    }
    @Test void reviewedAnswersUseSemanticGroundingWhileUnreviewedCasesKeepExactLabels()throws Exception {
        var reviewed=new PlatformEvaluation.Case("c1","question",List.of(),List.of("canonical.md"),List.of("a long canonical quote"),List.of(),List.of(),"","","reference","",false,"",json.nullNode(),"derived_retrieval","confirmed","kb",List.of());
        var exactOnly=new PlatformEvaluation.Case("c2","question",List.of(),List.of("canonical.md"),List.of("a long canonical quote"),List.of(),List.of(),"","","","",false,"",json.nullNode(),"manual","confirmed","kb",List.of());
        var checks=Map.of("status",true,"answerKind",true,"successfulTools",true,"forbiddenText",true,"toolArguments",true,"answerCoverage",false,"citedSources",false);
        assertTrue(PlatformEvaluation.taskChecksPass(reviewed,checks));
        assertFalse(PlatformEvaluation.taskChecksPass(exactOnly,checks));

        var supported=json.readTree("{\"checks\":{\"answerCoverage\":false,\"citedSources\":false},\"modelReview\":{\"checks\":{\"citationSupport\":{\"status\":\"passed\"}}}}");
        var unsupported=json.readTree("{\"checks\":{\"answerCoverage\":true,\"citedSources\":true},\"modelReview\":{\"checks\":{\"citationSupport\":{\"status\":\"failed\"}}}}");
        assertTrue(PlatformEvaluation.groundingPassed(reviewed,supported));
        assertFalse(PlatformEvaluation.groundingPassed(reviewed,unsupported));
        assertFalse(PlatformEvaluation.groundingPassed(exactOnly,supported));
    }
}
