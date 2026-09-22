package org.example.controller;

import org.example.platform.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP compatibility must use the exact same retrieval scope/service as the Agent. */
class ConsoleRecallTest {
    private final KnowledgeSearch search=mock(KnowledgeSearch.class);
    private final KnowledgeIngestion ingestion=mock(KnowledgeIngestion.class);
    private final KnowledgeSearch.Scope scope=new KnowledgeSearch.Scope(List.of("existing-knowledge"),Set.of("existing-data"),List.of());
    private final MockMvc mvc=MockMvcBuilders.standaloneSetup(new LegacyKnowledgeController(search,ingestion,mock(DocumentCatalog.class),mock(SourceStorage.class))).setControllerAdvice(new PlatformExceptionHandler()).build();
    @Test void overridesAreRequestLocalAndVectorRankSurvivesReranking()throws Exception {
        when(search.scope(List.of("existing-knowledge"))).thenReturn(scope);
        var a=document("a",0.3,null);var b=document("b",0.9,null);var c=document("c",1.4,null);var candidates=List.of(a,b,c);
        when(search.search(scope,"slow","semantic",8,2,false)).thenReturn(result(candidates,List.of(a,b)));
        when(search.search(scope,"slow","semantic",10,2,true)).thenReturn(result(candidates,List.of(c.scored(1.4,null,0.95,"vector"),a.scored(0.3,null,0.87,"vector"))));
        mvc.perform(post("/api/console/recall/test").contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"slow\",\"topK\":2,\"candidateTopK\":8,\"rerankEnabled\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rerankAttempted").value(false)).andExpect(jsonPath("$.topDocuments[0].vectorRank").value(1));
        mvc.perform(post("/api/console/recall/test").contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"slow\",\"topK\":2}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rerankApplied").value(true)).andExpect(jsonPath("$.topDocuments[0].sourceFile").value("c.md"))
                .andExpect(jsonPath("$.topDocuments[0].vectorRank").value(3)).andExpect(jsonPath("$.topDocuments[0].content").value("c".repeat(400)));
        verify(search).search(scope,"slow","semantic",8,2,false);verify(search).search(scope,"slow","semantic",10,2,true);
    }
    @Test void rejectsInvalidInputsBeforeCallingServices()throws Exception {
        for(String body:List.of("{\"query\":\" \"}","{\"query\":\"x\",\"topK\":0}","{\"query\":\"x\",\"topK\":11}","{\"query\":\"x\",\"candidateTopK\":101}","{\"query\":\"x\",\"topK\":5,\"candidateTopK\":4}","{\"query\":\""+"x".repeat(4001)+"\"}"))
            mvc.perform(post("/api/console/recall/test").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(search,ingestion);
    }
    @Test void malformedRequestsRemainClientErrors()throws Exception {
        mvc.perform(post("/api/console/recall/test").contentType(MediaType.APPLICATION_JSON).content("{broken")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/console/documents/preview")).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/console/documents/upload")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/console/documents/upload")).andExpect(status().isBadRequest());
        verifyNoInteractions(search,ingestion);
    }
    @Test void returnsActualEvidenceWithoutTheOld300CharacterTruncation()throws Exception {
        when(search.scope(anyList())).thenReturn(scope);var documents=java.util.stream.IntStream.range(0,10).mapToObj(i->document("doc"+i,i,null)).toList();
        when(search.search(scope,"all","semantic",10,10,false)).thenReturn(result(documents,documents));
        mvc.perform(post("/api/console/recall/test").contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"all\",\"topK\":10,\"rerankEnabled\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.topDocuments",hasSize(10))).andExpect(jsonPath("$.topDocuments[9].content",hasLength(1600)));
    }
    private PlatformChunk document(String id,double distance,Double rank){return new PlatformChunk(id,id,"v","existing-data",id+".md",id,0,0,id.length()*400,id.repeat(400),distance,null,rank,"vector");}
    private KnowledgeSearch.SearchResult result(List<PlatformChunk> candidates,List<PlatformChunk> documents){return new KnowledgeSearch.SearchResult("slow","semantic","candidates",false,List.of(),candidates.size(),candidates,documents,10,0,5,15);}
}
