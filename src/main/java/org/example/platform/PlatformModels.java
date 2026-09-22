package org.example.platform;

import java.util.List;

/** Stable business contracts shared by the console, runtime and tools. */
public final class PlatformModels {
    private PlatformModels() {}
    public record Dataset(String id, String name, String description, String parentId) {}
    public record KnowledgeBase(String id, String name, String description, String retrievalMode, List<String> datasetIds) {}
    public record ToolSet(String id, String name, String description, List<String> toolIds) {}
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record AgentConfig(String name, String description, String instructions, String greeting,
                              List<String> knowledgeBaseIds, List<String> toolSetIds,
                              String strategy, int maxToolCalls, int timeoutSeconds, double temperature,
                              int candidateTopK, int topK, int maxOutputTokens, int schemaVersion, boolean allowGeneralKnowledge,
                              String chatModel) {
        public AgentConfig(String name,String description,String instructions,String greeting,List<String> knowledgeBaseIds,
                List<String> toolSetIds,String strategy,int maxToolCalls,int timeoutSeconds,double temperature,
                int candidateTopK,int topK,int maxOutputTokens,int schemaVersion,boolean allowGeneralKnowledge) {
            this(name,description,instructions,greeting,knowledgeBaseIds,toolSetIds,strategy,maxToolCalls,timeoutSeconds,
                    temperature,candidateTopK,topK,maxOutputTokens,schemaVersion,allowGeneralKnowledge,null);
        }
        public AgentConfig(String name,String description,String instructions,String greeting,List<String> knowledgeBaseIds,
                List<String> toolSetIds,String strategy,int maxToolCalls,int timeoutSeconds,double temperature,
                int candidateTopK,int topK,int maxOutputTokens,int schemaVersion) {
            this(name,description,instructions,greeting,knowledgeBaseIds,toolSetIds,strategy,maxToolCalls,timeoutSeconds,
                    temperature,candidateTopK,topK,maxOutputTokens,schemaVersion,false);
        }
        public AgentConfig(String name,String description,String instructions,String greeting,List<String> knowledgeBaseIds,
                List<String> toolSetIds,String strategy,int maxToolCalls,int timeoutSeconds,double temperature,
                int candidateTopK,int topK,int maxOutputTokens) {
            this(name,description,instructions,greeting,knowledgeBaseIds,toolSetIds,strategy,maxToolCalls,timeoutSeconds,
                    temperature,candidateTopK,topK,maxOutputTokens,2);
        }
    }
    public record Agent(String id, int version, boolean enabled, AgentConfig config) {}
    public record DocumentVersion(String documentId, String datasetId, String path, String version,
                                  String contentHash, String sourcePath, int contentLength, int chunkCount,
                                  String collection, String status) {}
    public record DocumentInfo(String id, String datasetId, String path, String version, String status,
                               String error, int contentLength, int chunkCount, String updatedAt) {}
}
