package org.example.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.SearchResults;
import io.milvus.param.R;
import io.milvus.param.dml.SearchParam;
import io.milvus.response.SearchResultsWrapper;
import lombok.Getter;
import lombok.Setter;
import org.example.constant.MilvusConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 向量搜索服务
 * 负责从 Milvus 中搜索相似向量
 */
@Service
public class VectorSearchService {

    private static final Logger logger = LoggerFactory.getLogger(VectorSearchService.class);

    @Autowired
    @org.springframework.context.annotation.Lazy
    private MilvusServiceClient milvusClient;

    @Autowired
    private VectorEmbeddingService embeddingService;

    @Autowired private KnowledgeFiles knowledgeFiles;

    @Value("${milvus.search.nprobe:10}")
    private int nprobe;

    /**
     * 搜索相似文档
     * 
     * @param query 查询文本
     * @param topK 返回最相似的K个结果
     * @return 搜索结果列表
     */
    public List<SearchResult> searchSimilarDocuments(String query, int topK) {
        if (knowledgeFiles != null) {
            knowledgeFiles.lock().readLock().lock();
            if (!knowledgeFiles.indexHealthy()) { knowledgeFiles.lock().readLock().unlock(); throw new IllegalStateException("索引更新尚未恢复，请使用原文检索"); }
        }
        try {
            logger.info("开始搜索相似文档, 查询: {}, topK: {}", query, topK);

            // 1. 将查询文本向量化
            List<Float> queryVector = embeddingService.generateQueryVector(query);
            logger.debug("查询向量生成成功, 维度: {}", queryVector.size());

            // 2. 构建搜索参数
            SearchParam searchParam = SearchParam.newBuilder()
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .withVectorFieldName("vector")
                    .withVectors(Collections.singletonList(queryVector))
                    .withTopK(topK)
                    .withMetricType(io.milvus.param.MetricType.L2)
                    .withOutFields(List.of("id", "content", "metadata"))
                    .withParams("{\"nprobe\":" + Math.max(1, nprobe) + "}")
                    .build();

            // 3. 执行搜索
            R<SearchResults> searchResponse = milvusClient.search(searchParam);

            if (searchResponse.getStatus() != 0) {
                throw new RuntimeException("向量搜索失败: " + searchResponse.getMessage());
            }

            // 4. 解析搜索结果
            SearchResultsWrapper wrapper = new SearchResultsWrapper(searchResponse.getData().getResults());
            List<SearchResult> results = new ArrayList<>();

            for (int i = 0; i < wrapper.getRowRecords(0).size(); i++) {
                SearchResult result = new SearchResult();
                result.setId((String) wrapper.getIDScore(0).get(i).get("id"));
                result.setContent((String) wrapper.getFieldData("content", 0).get(i));
                result.setScore(wrapper.getIDScore(0).get(i).getScore());
                
                // 解析 metadata
                Object metadataObj = wrapper.getFieldData("metadata", 0).get(i);
                if (metadataObj != null) {
                    result.setMetadata(metadataObj.toString());
                }
                
                results.add(result);
            }

            logger.info("搜索完成, 找到 {} 个相似文档", results.size());
            return results;

        } catch (Exception e) {
            logger.error("搜索相似文档失败", e);
            throw new RuntimeException("搜索失败: " + e.getMessage(), e);
        } finally {
            if (knowledgeFiles != null) knowledgeFiles.lock().readLock().unlock();
        }
    }

    /**
     * 搜索结果类
     */
    @Setter
    @Getter
    public static class SearchResult {
        private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

        private String id;
        private String content;
        private float score;
        private Double rerankScore;
        private String metadata;

        /*
         * 升级 01：RAG 引用溯源字段。
         * 这些字段从 Milvus metadata JSON 中解析出来，后续用于在回答或前端中展示
         * “答案参考了哪个文件、哪个分片”，避免只有一段 content 却不知道来源。
         */
        private String sourcePath;
        private String sourceFile;
        private Integer chunkIndex;
        private Integer totalChunks;
        private String title;

        /**
         * 升级 01：设置原始 metadata 时，同步解析出更好读的来源字段。
         */
        public void setMetadata(String metadata) {
            this.metadata = metadata;
            parseMetadata(metadata);
        }

        /**
         * 升级 01：把 Milvus 返回的 metadata JSON 拆成 sourceFile、chunkIndex 等字段。
         */
        private void parseMetadata(String metadata) {
            if (metadata == null || metadata.isBlank()) {
                return;
            }

            try {
                JsonNode root = OBJECT_MAPPER.readTree(metadata);
                this.sourcePath = readText(root, "_source");
                this.sourceFile = readText(root, "_file_name");
                this.chunkIndex = readInt(root, "chunkIndex");
                this.totalChunks = readInt(root, "totalChunks");
                this.title = readText(root, "title");
            } catch (Exception e) {
                logger.debug("解析搜索结果 metadata 失败: {}", metadata, e);
            }
        }

        private String readText(JsonNode root, String fieldName) {
            JsonNode node = root.get(fieldName);
            return node != null && !node.isNull() ? node.asText() : null;
        }

        private Integer readInt(JsonNode root, String fieldName) {
            JsonNode node = root.get(fieldName);
            return node != null && node.canConvertToInt() ? node.asInt() : null;
        }

    }
}
