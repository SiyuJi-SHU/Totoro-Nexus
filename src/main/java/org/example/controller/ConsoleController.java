package org.example.controller;

import org.example.service.*;
import org.example.agent.tool.QueryMetricsTools;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/console")
public class ConsoleController {
    private final RetrievalPipelineService retrieval;
    private final QueryMetricsTools scenarios;
    @Value("${dashscope.embedding.model}") private String embeddingModel;
    @Value("${dashscope.rerank.model}") private String rerankModel;
    public ConsoleController(RetrievalPipelineService retrieval, QueryMetricsTools scenarios) {
        this.retrieval = retrieval; this.scenarios = scenarios;
    }
    // Archived Java contract; the HTTP path now delegates to KnowledgeSearch via LegacyKnowledgeController.
    public ResponseEntity<?> recall(@RequestBody RecallRequest request) {
        if (request == null || request.query() == null || request.query().isBlank() || request.query().length() > 4000)
            return ResponseEntity.badRequest().body(Map.of("message", "请输入 1–4000 字的查询"));
        int top = request.topK() == null ? 3 : request.topK();
        int candidates = request.candidateTopK() == null ? 10 : request.candidateTopK();
        if (top < 1 || top > 10 || candidates < top || candidates > 100)
            return ResponseEntity.badRequest().body(Map.of("message", "候选数须不小于返回数且不超过100，返回数为1–10"));
        var result = retrieval.retrieve(request.query().trim(), top, candidates, !Boolean.FALSE.equals(request.rerankEnabled()));
        return ResponseEntity.ok(RetrievalTrace.console(request.query().trim(), embeddingModel, rerankModel, result));
    }
    @GetMapping("/scenarios")
    public Object scenarios() { return scenarios.getScenarios(); }
    public record RecallRequest(String query, Integer topK, Integer candidateTopK, Boolean rerankEnabled) {}
}
