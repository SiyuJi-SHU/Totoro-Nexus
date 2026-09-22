package org.example.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.service.RetrievalPipelineService;
import org.example.service.VectorSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内部文档查询工具
 * 使用 RAG (Retrieval-Augmented Generation) 从内部知识库检索相关文档
 */
@Component
public class InternalDocsTools {
    
    private static final Logger logger = LoggerFactory.getLogger(InternalDocsTools.class);
    
    /** 工具名常量，用于动态构建提示词 */
    public static final String TOOL_QUERY_INTERNAL_DOCS = "queryInternalDocs";
    
    private final RetrievalPipelineService retrievalPipelineService;
    
    @Value("${rag.top-k:3}")
    private int topK = 3; // 默认值
    
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private org.example.service.KnowledgeRetrievalService knowledgeRetrieval;
    private org.example.service.KnowledgeRetrievalService.SearchSession searchSession = new org.example.service.KnowledgeRetrievalService.SearchSession();
    private boolean keywordAttempted;

    /** A ReAct conversation gets its own bounded cache and rewrite budget. */
    public InternalDocsTools forRequest() {
        InternalDocsTools scoped = new InternalDocsTools(retrievalPipelineService);
        scoped.knowledgeRetrieval = knowledgeRetrieval;
        scoped.topK = topK;
        return scoped;
    }

    @Tool(description = "Search knowledge-base filenames, headings and original document text by keywords. Independent of embeddings and Milvus. Use when semantic search failed or its excerpts do not answer the question. A second call rewrites keywords once and retries. No further rewriting is allowed. Results are candidate evidence, never confirmed causes.")
    public String searchKnowledgeByKeywords(@ToolParam(description = "Original question or precise service/error keywords; preserve negations and error codes") String query) {
        int stage = keywordAttempted ? 2 : 1;
        keywordAttempted = true;
        return boundedSearch(query, stage);
    }

    private String boundedSearch(String query, int stage) {
        try { return objectMapper.writeValueAsString(knowledgeRetrieval.search(query, searchSession, stage)); }
        catch (java.util.concurrent.CancellationException e) { throw e; }
        catch (Exception e) { return "{\"status\":\"error\",\"documents\":[],\"message\":\"知识库检索未完成，不得据此编造答案\"}"; }
    }
    
    /**
     * 构造函数注入依赖
     * Spring injects the shared retrieval pipeline used by both RAG and Agents.
     */
    @Autowired
    public InternalDocsTools(RetrievalPipelineService retrievalPipelineService) {
        this.retrievalPipelineService = retrievalPipelineService;
    }
    
    /**
     * 查询内部文档工具
     *
     * @param query 搜索查询，描述您要查找的信息
     * @return JSON 格式的搜索结果，包含相关文档内容、相似度分数和元数据
     */
    @Tool(description = "Use this tool to search internal documentation and knowledge base for relevant information. " +
            "It performs RAG (Retrieval-Augmented Generation) to find similar documents and extract processing steps. " +
            "This is useful when you need to understand internal procedures, best practices, or step-by-step guides " +
            "stored in the company's documentation.")
    public String queryInternalDocs(
            @ToolParam(description = "Search query describing what information you are looking for") 
            String query) {
        return queryDocuments(query, false);
    }

    /** UI evidence path, intentionally not registered as an LLM tool. */
    public String queryInternalDocsWithTrace(String query) {
        return queryDocuments(query, true);
    }

    private String queryDocuments(String query, boolean includeTrace) {
        if (knowledgeRetrieval != null) return boundedSearch(query, 0);
        try {
            RetrievalPipelineService.RetrievalResult retrieval =
                    retrievalPipelineService.retrieve(query, topK);
            List<VectorSearchService.SearchResult> searchResults = retrieval.getDocuments();

            if (searchResults.isEmpty() && !includeTrace) {
                return "{\"status\": \"no_results\", \"message\": \"No relevant documents found in the knowledge base.\"}";
            }
            
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", searchResults.isEmpty() ? "no_results" : retrieval.isDegraded() ? "degraded" : "ok");
            if (includeTrace) {
                response.put("retrieval", retrievalPipelineService.trace(query, "standard", retrieval));
            }
            response.put("documents", searchResults);
            response.put("rerankAttempted", retrieval.isRerankAttempted());
            response.put("rerankApplied", retrieval.isRerankApplied());
            response.put("degraded", retrieval.isDegraded());
            response.put("retrievalLatencyMs", retrieval.getRetrievalLatencyMs());
            if (retrieval.getFailureType() != null) {
                response.put("failureType", retrieval.getFailureType());
                response.put("timeout", retrieval.isTimeout());
            }
            return objectMapper.writeValueAsString(response);
            
        } catch (Exception e) {
            logger.error("[工具错误] queryInternalDocs 执行失败", e);
            return String.format("{\"status\": \"error\", \"message\": \"Failed to query internal docs: %s\"}", 
                    e.getMessage());
        }
    }
}
