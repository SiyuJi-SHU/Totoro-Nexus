package org.example.platform;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;

@RestController
@RequestMapping("/api/platform/admin/evaluation")
public class PlatformEvaluationController {
    private final PlatformEvaluation evaluations;private final PlatformIdentity identity;
    public PlatformEvaluationController(PlatformEvaluation evaluations,PlatformIdentity identity){this.evaluations=evaluations;this.identity=identity;}
    public record Import(String name,String kind,String knowledgeBaseId,String targetAgentId,String targetStrategy,JsonNode cases){}
    @GetMapping("/workspaces") public Object workspaces(){return evaluations.workspaces();}
    @GetMapping("/sets") public Object sets(){return evaluations.suites();}
    @PostMapping("/sets") public Object create(@RequestBody Import input){return evaluations.importSuite(input.name(),input.kind(),input.cases(),input.knowledgeBaseId(),input.targetAgentId(),input.targetStrategy());}
    @PostMapping("/sets/{id}/cases") public Object append(@PathVariable String id,@RequestBody JsonNode input){return evaluations.appendCase(id,input);}
    @DeleteMapping("/sets/{id}/cases/{caseId}") public Object deleteCase(@PathVariable String id,@PathVariable String caseId){return evaluations.deleteCase(id,caseId);}
    public record Generate(String knowledgeBaseId,Integer count,String language,java.util.List<String> excludeChunkIds,java.util.List<String> excludeQuestions){}
    @PostMapping("/generate/retrieval") public Object generate(@RequestBody Generate input)throws Exception{return evaluations.generateRetrievalCases(input.knowledgeBaseId(),input.count()==null?6:input.count(),input.language(),input.excludeChunkIds(),input.excludeQuestions());}
    public record BuildAgentCases(String knowledgeBaseId,String agentId){}
    @PostMapping("/generate/agent-knowledge") public Object deriveKnowledge(@RequestBody BuildAgentCases input){return evaluations.deriveKnowledgeAgentSuite(input.knowledgeBaseId(),input.agentId());}
    @PostMapping("/generate/agent-workflow") public Object buildWorkflow(@RequestBody BuildAgentCases input){return evaluations.createWorkflowSuite(input.knowledgeBaseId(),input.agentId());}
    public record Start(String setId,PlatformEvaluation.Config config){}
    @PostMapping("/jobs") public Object start(Authentication user,@RequestBody Start input){return evaluations.start(identity.userId(user),input.setId(),input.config());}
    @GetMapping("/jobs") public Object jobs(){return evaluations.jobs();}
    @GetMapping("/jobs/{id}") public Object job(@PathVariable String id){return evaluations.job(id);}
    @PostMapping("/jobs/{id}/retry-incomplete") public Object retryIncomplete(Authentication user,@PathVariable String id){return evaluations.retryIncomplete(identity.userId(user),id);}
    @PostMapping("/jobs/{id}/cancel") public Object cancel(Authentication user,@PathVariable String id){return evaluations.cancel(identity.userId(user),id);}
}
