package org.example.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.milvus.client.MilvusServiceClient;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.orm.iterator.QueryIterator;
import io.milvus.param.R;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.QueryIteratorParam;
import io.milvus.param.dml.UpsertParam;
import org.example.constant.MilvusConstants;
import org.springframework.stereotype.Service;

import java.util.*;

/** Exact-source operations used by the console; no embeddings are generated here. */
@Service
public class ConsoleDocumentIndexService {
    private final MilvusServiceClient client;
    private final Gson gson = new Gson();

    public ConsoleDocumentIndexService(@org.springframework.context.annotation.Lazy MilvusServiceClient client) { this.client = client; }

    public Map<String, SourceStats> statistics() {
        Map<String, SourceStats> result = new HashMap<>();
        for (var row : query("id != \"\"", List.of("id", "metadata"))) {
            JsonObject metadata = gson.fromJson(row.get("metadata").toString(), JsonObject.class);
            if (!metadata.has("_source")) continue;
            String source = metadata.get("_source").getAsString();
            long expected = metadata.has("totalChunks") ? metadata.get("totalChunks").getAsLong() : 0;
            SourceStats old = result.getOrDefault(source, new SourceStats(0, expected));
            result.put(source, new SourceStats(old.count() + 1, Math.max(old.expected(), expected)));
        }
        return result;
    }

    public List<Map<String, Object>> snapshot(String source) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (var row : query(expression(source), List.of("id", "content", "vector", "metadata"))) {
            Map<String, Object> fields = new LinkedHashMap<>(row.getFieldValues());
            fields.put("metadata", gson.fromJson(fields.get("metadata").toString(), JsonObject.class));
            result.add(fields);
        }
        return result;
    }

    public long delete(String source) {
        var response = client.delete(DeleteParam.newBuilder()
                .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME).withExpr(expression(source)).build());
        requireSuccess(response);
        if (!snapshot(source).isEmpty()) throw new IllegalStateException("删除后仍有残留向量");
        return response.getData().getDeleteCnt();
    }

    public void restore(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return;
        List<InsertParam.Field> fields = new ArrayList<>();
        for (String name : List.of("id", "content", "vector", "metadata")) {
            List<Object> values = new ArrayList<>();
            for (var row : rows) {
                Object value = row.get(name);
                if (name.equals("metadata")) value = value instanceof JsonObject ? value : gson.toJsonTree(value).getAsJsonObject();
                if (name.equals("vector") && value instanceof List<?> vector) value = vector.stream().map(n -> ((Number)n).floatValue()).toList();
                values.add(value);
            }
            fields.add(new InsertParam.Field(name, values));
        }
        requireSuccess(client.upsert(UpsertParam.newBuilder()
                .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME).withFields(fields).build()));
    }

    private List<io.milvus.response.QueryResultsWrapper.RowRecord> query(String expression, List<String> fields) {
        var response = client.queryIterator(QueryIteratorParam.newBuilder()
                .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                .withConsistencyLevel(ConsistencyLevelEnum.STRONG)
                .withExpr(expression).withOutFields(fields).withBatchSize(512L).build());
        requireSuccess(response);
        QueryIterator iterator = response.getData();
        if (iterator == null) throw new IllegalStateException("向量查询未返回迭代器");
        try {
            List<io.milvus.response.QueryResultsWrapper.RowRecord> rows = new ArrayList<>();
            while (true) {
                var batch = iterator.next();
                if (batch == null || batch.isEmpty()) return rows;
                rows.addAll(batch);
            }
        } finally { iterator.close(); }
    }

    static String expression(String source) {
        // JSON string encoding prevents quotes/backslashes in a filename changing the filter.
        return "metadata[\"_source\"] == " + new Gson().toJson(source);
    }

    public void deleteIds(List<String> ids) {
        if (ids.isEmpty()) return;
        requireSuccess(client.delete(DeleteParam.newBuilder().withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                .withExpr("id in " + gson.toJson(ids)).build()));
        if (!query("id in " + gson.toJson(ids), List.of("id")).isEmpty()) throw new IllegalStateException("向量清理尚未完成");
    }

    private static void requireSuccess(R<?> response) {
        if (response == null || response.getStatus() != 0)
            throw new IllegalStateException("向量库操作失败" + (response == null ? "" : "：" + response.getMessage()));
    }

    public record SourceStats(long count, long expected) {}
}
