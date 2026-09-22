package org.example.platform;

import com.google.gson.*;
import io.milvus.client.MilvusServiceClient;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.grpc.DataType;
import io.milvus.param.*;
import io.milvus.param.collection.*;
import io.milvus.param.dml.*;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.SearchResultsWrapper;
import org.example.constant.MilvusConstants;
import org.example.service.VectorEmbeddingService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import java.util.*;

/** New collection is isolated from the legacy baseline; immutable versions are filtered before ANN ranking. */
@Service
public class VersionedVectorIndex {
    public static final String COLLECTION="agent_knowledge_v1";
    private final ObjectProvider<MilvusServiceClient> provider;
    private final VectorEmbeddingService embeddings;
    private final Gson gson=new Gson();
    private volatile boolean initialized;
    public VersionedVectorIndex(ObjectProvider<MilvusServiceClient> provider,VectorEmbeddingService embeddings){this.provider=provider;this.embeddings=embeddings;}
    private synchronized MilvusServiceClient client() {
        var client=provider.getObject();
        if(initialized)return client;
        var exists=client.hasCollection(HasCollectionParam.newBuilder().withCollectionName(COLLECTION).build());check(exists);
        if(!Boolean.TRUE.equals(exists.getData())) {
            var schema=CollectionSchemaParam.newBuilder()
                    .addFieldType(FieldType.newBuilder().withName("id").withDataType(DataType.VarChar).withMaxLength(128).withPrimaryKey(true).build())
                    .addFieldType(FieldType.newBuilder().withName("vector").withDataType(DataType.FloatVector).withDimension(MilvusConstants.VECTOR_DIM).build())
                    .addFieldType(FieldType.newBuilder().withName("content").withDataType(DataType.VarChar).withMaxLength(65535).build())
                    .addFieldType(FieldType.newBuilder().withName("metadata").withDataType(DataType.JSON).build()).build();
            check(client.createCollection(CreateCollectionParam.newBuilder().withCollectionName(COLLECTION).withSchema(schema).withShardsNum(2).build()));
        }
        check(client.createIndex(CreateIndexParam.newBuilder().withCollectionName(COLLECTION).withFieldName("vector")
                .withIndexType(IndexType.IVF_FLAT).withMetricType(MetricType.L2).withExtraParam("{\"nlist\":128}").withSyncMode(true).build()));
        check(client.loadCollection(LoadCollectionParam.newBuilder().withCollectionName(COLLECTION).build()));
        initialized=true;return client;
    }
    public void prepare(List<PlatformChunk> chunks) {
        List<List<Float>> vectors=new ArrayList<>();
        for(int start=0;start<chunks.size();start+=10) {
            org.example.service.ChatModelFactory.checkCancelled();
            int end=Math.min(chunks.size(),start+10);
            vectors.addAll(embeddings.generateEmbeddings(chunks.subList(start,end).stream().map(PlatformChunk::retrievalText).toList()));
        }
        prepareWithVectors(chunks,vectors);
    }
    public void prepareWithVectors(List<PlatformChunk> chunks,List<List<Float>> vectors) {
        if(chunks.isEmpty()||chunks.size()!=vectors.size())throw new IllegalArgumentException("分块与向量数量不一致");
        List<JsonObject> metadata=new ArrayList<>();
        for(int i=0;i<chunks.size();i++) {
            if(vectors.get(i).size()!=MilvusConstants.VECTOR_DIM)throw new IllegalArgumentException("向量维度不一致");
            metadata.add(gson.toJsonTree(chunks.get(i)).getAsJsonObject());
        }
        var fields=List.of(new InsertParam.Field("id",chunks.stream().map(PlatformChunk::id).toList()),
                new InsertParam.Field("content",chunks.stream().map(PlatformChunk::content).toList()),new InsertParam.Field("vector",vectors),new InsertParam.Field("metadata",metadata));
        check(client().upsert(UpsertParam.newBuilder().withCollectionName(COLLECTION).withFields(fields).build()));
        if(count(chunks.get(0).version())!=chunks.size())throw new IllegalStateException("新向量分块数不完整");
    }
    public int count(String version) {
        var result=client().queryIterator(QueryIteratorParam.newBuilder().withCollectionName(COLLECTION)
                .withExpr("metadata[\"version\"] == "+gson.toJson(version)).withOutFields(List.of("id"))
                .withConsistencyLevel(ConsistencyLevelEnum.STRONG).withBatchSize(512L).build());check(result);
        var iterator=result.getData();int count=0;
        try {while(true){var batch=iterator.next();if(batch==null||batch.isEmpty())return count;count+=batch.size();}}
        finally {iterator.close();}
    }
    public List<PlatformChunk> search(String query,Collection<String> versions,int limit) {
        if(versions.isEmpty())return List.of();
        var vector=embeddings.generateQueryVector(query);
        var result=client().search(SearchParam.newBuilder().withCollectionName(COLLECTION).withVectorFieldName("vector")
                .withVectors(List.of(vector)).withTopK(limit).withMetricType(MetricType.L2).withParams("{\"nprobe\":10}")
                .withExpr("metadata[\"version\"] in "+gson.toJson(versions)).withOutFields(List.of("id","content","metadata"))
                .withConsistencyLevel(ConsistencyLevelEnum.STRONG).build());check(result);
        var wrapper=new SearchResultsWrapper(result.getData().getResults());List<PlatformChunk> hits=new ArrayList<>();
        for(int i=0;i<wrapper.getRowRecords(0).size();i++) {
            var chunk=gson.fromJson(wrapper.getFieldData("metadata",0).get(i).toString(),PlatformChunk.class);
            hits.add(chunk.scored((double)wrapper.getIDScore(0).get(i).getScore(),null,null,"vector"));
        }
        return List.copyOf(hits);
    }
    private static void check(R<?> result) { if(result==null||result.getStatus()!=0)throw new IllegalStateException("向量服务请求失败"+(result==null?"":": "+result.getMessage())); }
}
