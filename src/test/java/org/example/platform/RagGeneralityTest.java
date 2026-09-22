package org.example.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RagGeneralityTest {
    @TempDir Path temp;
    PlatformChunk chunk(String id,String doc,int start,String content){return new PlatformChunk(id,doc,"v1","data",doc+".md","Body",0,start,start+content.length(),content,null,null,null,"source");}
    AgentToolRegistry.Evidence evidence(String id,String version,int start,String content){return new AgentToolRegistry.Evidence(id,"doc",version,"a.md","Body",start,start+content.length(),content,"document");}

    @Test void labelMustQuoteAnswerTextAndPreserveNumbersAndCrLf() {
        String content="# Operations\r\nAuthor: unrelated metadata\r\n\r\nRun GET _cluster/health to inspect health.\r\nKeep threshold 42.\r\n";
        assertEquals(List.of("Run GET _cluster/health to inspect health."),EvaluationLabels.resolve(content,List.of("Run GET _cluster/health to inspect health.")));
        assertTrue(EvaluationLabels.resolve(content,List.of("Keep threshold 43.")).isEmpty());
        String original="Carry iron to Temesa4 and exchange it for copper.";
        assertTrue(EvaluationLabels.resolve(original,List.of(original.replace("Temesa4","Temesa"))).isEmpty());
        assertEquals(original,EvaluationLabels.resolve(original,List.of(original)).get(0));
        assertTrue(EvaluationLabels.covers(List.of("health.\nKeep threshold 42."),List.of(content)));
        assertFalse(EvaluationLabels.covers(List.of("health.\nKeep threshold 43."),List.of(content)));
    }
    @Test void samplingReachesLateDocumentsAndDoesNotRepeatFirstTenSources() {
        List<PlatformChunk> chunks=new ArrayList<>();
        for(int d=0;d<25;d++)for(int n=0;n<30;n++)chunks.add(chunk("c-"+d+"-"+n,"doc-"+d,n*100,"Detailed body fact for a general domain. ".repeat(4)));
        Set<String> used=new HashSet<>(),docs=new HashSet<>();
        for(int i=0;i<3;i++)for(var c:EvaluationLabels.sample(chunks,used,10)){assertTrue(used.add(c.id()));docs.add(c.documentId());}
        assertEquals(25,docs.size());
        assertTrue(EvaluationLabels.sample(chunks,Set.of(),10).stream().anyMatch(c->c.start()>1000));
    }
    @Test void indexEnumerationIncludesEntriesBeyondLegacyFiveHundred() throws Exception {
        var config=new org.example.config.FileUploadConfig();config.setPath(temp.toString());
        var index=new LexicalIndex(new KnowledgeFiles(config));try {
            List<PlatformChunk> chunks=new ArrayList<>();for(int n=0;n<720;n++)chunks.add(chunk("c-"+n,"doc",n*20,"Data sentence."));
            index.prepare("v1",chunks);assertEquals(720,index.list(List.of("v1"),Integer.MAX_VALUE).size());
            assertEquals(12,index.list(List.of("v1"),12).size());
        }finally{index.close();}
    }
    @Test void contextBudgetCountsOverlapsOnceButKeepsVersionsSeparate() {
        var a=evidence("a","v1",0,"a".repeat(20000));var b=evidence("b","v1",19000,"a".repeat(10000));
        assertEquals(29000,EvidenceContext.size(List.of(a,b)));
        assertEquals(39000,EvidenceContext.size(List.of(a,b,evidence("c","v2",19000,"a".repeat(10000)))));
    }
    @Test void crossLanguageRewriteRecoversModerateMissesWithoutRewritingConfidentResults() {
        var weakEnglish=new PlatformChunk("en","doc","v1","data","guide.md","Body",0,0,80,
                "The responder should verify the service health before restarting the worker.",null,null,.19,"vector");
        var moderateEnglish=weakEnglish.scored(null,null,.34,"vector");
        var strongEnglish=weakEnglish.scored(null,null,.72,"vector");
        assertEquals(KnowledgeSearch.Language.CJK,KnowledgeSearch.dominantLanguage("服务无法启动时应该先检查什么？"));
        assertEquals(KnowledgeSearch.Language.LATIN,KnowledgeSearch.dominantLanguage(List.of(weakEnglish)));
        assertTrue(KnowledgeSearch.needsCrossLanguageRewrite("服务无法启动时应该先检查什么？",List.of(weakEnglish)));
        assertTrue(KnowledgeSearch.needsCrossLanguageRewrite("服务无法启动时应该先检查什么？",List.of(moderateEnglish)));
        assertFalse(KnowledgeSearch.needsCrossLanguageRewrite("服务无法启动时应该先检查什么？",List.of(strongEnglish)));
        assertFalse(KnowledgeSearch.needsCrossLanguageRewrite("What should the responder verify first?",List.of(weakEnglish)));
    }
    @Test void expansionReadsBeyondTwentyThousandAndMergesOverlap() throws Exception {
        String content="Introduction. ".repeat(2500)+"Unique late answer: use the blue connector.\r\n"+"Appendix. ".repeat(500);
        var source=new DocumentReadingService.Source("doc","v1","manual.md",content);
        var version=mock(PlatformModels.DocumentVersion.class);when(version.documentId()).thenReturn("doc");when(version.version()).thenReturn("v1");
        var sources=mock(SourceStorage.class);when(sources.read(version)).thenReturn(source);
        var search=new KnowledgeSearch(null,null,sources,null,null,null,new DocumentReadingService());
        int start=content.indexOf("Unique late");var hits=List.of(chunk("a","doc",start,content.substring(start,start+50)),chunk("b","doc",start+20,content.substring(start+20,start+80)));
        var windows=search.contextWindows(new KnowledgeSearch.Scope(List.of(),Set.of(),List.of(version)),hits);
        assertEquals(1,windows.size());var window=windows.get(0);assertTrue(window.start()>20000);assertTrue(window.content().contains("blue connector"));
        assertEquals(content.substring(window.start(),window.end()),window.content());
    }
    @Test void contextExpansionStaysInsideTheNearestSectionAndHasABoundedParentWindow() throws Exception {
        String first="# First\n\n"+"unrelated paragraph\n\n".repeat(400);
        String second="# Second\n\n"+"narrative context\n\n".repeat(400);
        String content=first+second;
        var source=new DocumentReadingService.Source("doc","v1","manual.md",content);
        var version=mock(PlatformModels.DocumentVersion.class);when(version.documentId()).thenReturn("doc");when(version.version()).thenReturn("v1");
        var sources=mock(SourceStorage.class);when(sources.read(version)).thenReturn(source);
        var search=new KnowledgeSearch(null,null,sources,null,null,null,new DocumentReadingService());
        int start=content.indexOf("narrative context",first.length()+2500);
        var window=search.contextWindows(new KnowledgeSearch.Scope(List.of(),Set.of(),List.of(version)),List.of(chunk("a","doc",start,"narrative context"))).get(0);
        assertTrue(window.start()>=first.length());
        assertTrue(window.end()-window.start()<=6000);
        assertTrue(window.content().contains("narrative context"));
        assertFalse(window.content().contains("unrelated paragraph"));
    }
    @Test void contextExpansionDoesNotDropTrailingContextAtADistantParagraphBoundary() throws Exception {
        String content="# Section\n\n"+"a".repeat(2980)+"\n\n"+"b".repeat(2998)+"\n\n"+"c".repeat(4000);
        var source=new DocumentReadingService.Source("doc","v1","manual.md",content);
        var version=mock(PlatformModels.DocumentVersion.class);when(version.documentId()).thenReturn("doc");when(version.version()).thenReturn("v1");
        var sources=mock(SourceStorage.class);when(sources.read(version)).thenReturn(source);
        var search=new KnowledgeSearch(null,null,sources,null,null,null,new DocumentReadingService());
        int hitStart=content.indexOf("b")+1900,marker=content.lastIndexOf("\n\n")+2+900;
        var window=search.contextWindows(new KnowledgeSearch.Scope(List.of(),Set.of(),List.of(version)),List.of(chunk("a","doc",hitStart,content.substring(hitStart,hitStart+30)))).get(0);
        assertTrue(window.start()<=hitStart&&window.end()>=hitStart+30);
        assertTrue(window.start()<=marker&&window.end()>marker,"window "+window.start()+"-"+window.end()+" must include marker "+marker+" instead of snapping to a distant prior paragraph boundary");
        assertTrue(window.end()-window.start()<=6000);
    }
    @Test void citationRepairCannotEditClaimOrReplaceAValueWithInventedNumber() throws Exception {
        var json=new ObjectMapper();String content="Cargo is iron to Temesa4 in exchange for copper.";
        var answer=json.readTree("{\"findings\":[{\"text\":\"Cargo is iron.\",\"citations\":[{\"id\":\"a\",\"quote\":\"Cargo is iron to Temesa in exchange for copper.\"}]}]}");
        var originals=Map.of("findings/0/0",answer.path("findings").get(0).path("citations").get(0));
        var sources=Map.of("a",evidence("a","v1",0,content));
        var replacements=json.valueToTree(List.of(Map.of("path","findings/0/0","quotes",List.of(content))));
        var repaired=json.readTree(CitationRepair.apply(answer.deepCopy(),replacements,originals,sources,json));
        assertEquals("Cargo is iron.",repaired.path("findings").get(0).path("text").asText());
        assertEquals(content,repaired.path("findings").get(0).path("citations").get(0).path("quote").asText());
        var fabricated=json.valueToTree(List.of(Map.of("path","findings/0/0","quotes",List.of(content.replace("Temesa4","Temesa9")))));
        assertEquals(answer,json.readTree(CitationRepair.apply(answer.deepCopy(),fabricated,originals,sources,json)));
    }
    @Test void selectedSpansRoundTripFootnotesTablesAndSurrogatesWithoutInventingQuotes()throws Exception {
        String content="Context line.\r\n".repeat(55)+"| cobalt | Nerava4 | wheat |\r\n"+"🌊".repeat(340);
        var spans=SourceSpans.split(content);assertEquals(content,spans.stream().map(SourceSpans.Span::text).reduce("",String::concat));
        for(var span:spans){assertEquals(content.substring(span.start(),span.end()),span.text());assertFalse(Character.isLowSurrogate(span.text().charAt(0)));}
        var selected=spans.stream().filter(s->s.text().contains("Nerava4")).findFirst().orElseThrow();var json=new ObjectMapper();
        String draft=json.writeValueAsString(Map.of("findings",List.of(Map.of("text","Cobalt is exchanged for wheat at Nerava.","certainty","supported","citations",List.of(Map.of("id","a","spanIds",List.of(selected.id())))))));
        var resolved=json.readTree(SourceSpans.resolveDraft(draft,Map.of("a",evidence("a","v1",0,content)),json));
        assertEquals(selected.text(),resolved.path("findings").get(0).path("citations").get(0).path("quote").asText());
        assertFalse(resolved.toString().contains("spanIds"));
        var invalid=json.readTree(SourceSpans.resolveDraft(draft.replace(selected.id(),"s9999"),Map.of("a",evidence("a","v1",0,content)),json));
        assertEquals("",invalid.path("findings").get(0).path("citations").get(0).path("quote").asText());
    }
    @Test void providerRejectionIsNotReportedAsMissingKnowledgeOrGenericBadParameters() {
        var error=new org.springframework.ai.retry.NonTransientAiException("400 - {\"code\":\"DataInspectionFailed\"}");
        assertTrue(AgentRuntime.safeError(error).contains("DataInspectionFailed"));
        assertTrue(AgentRuntime.safeError(error).contains("不是知识库召回失败"));
    }
}
