package com.example.agentrag.vector;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.request.data.BaseVector;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class VectorStore {

    private final MilvusClientV2 client;
    private final String collectionName;
    private final int dimension;

    public VectorStore(MilvusClientV2 client,
                       @Value("${app.milvus.collection-name}") String collectionName,
                       @Value("${app.milvus.dimension}") int dimension) {
        this.client = client;
        this.collectionName = collectionName;
        this.dimension = dimension;
    }

    @PostConstruct
    public void ensureCollection() {
        if (exists()) {
            return;
        }
        List<CreateCollectionReq.FieldSchema> fields = List.of(
                CreateCollectionReq.FieldSchema.builder().name("id").dataType(DataType.VarChar)
                        .maxLength(32).isPrimaryKey(true).autoID(false).build(),
                CreateCollectionReq.FieldSchema.builder().name("document_id").dataType(DataType.VarChar)
                        .maxLength(32).build(),
                CreateCollectionReq.FieldSchema.builder().name("embedding").dataType(DataType.FloatVector)
                        .dimension(dimension).build()
        );
        IndexParam vectorIndex = IndexParam.builder()
                .fieldName("embedding")
                .indexName("embedding_idx")
                .indexType(IndexParam.IndexType.HNSW)
                .metricType(IndexParam.MetricType.COSINE)
                .extraParams(Map.of("M", "16", "efConstruction", "128"))
                .build();
        client.createCollection(CreateCollectionReq.builder()
                .collectionName(collectionName)
                .collectionSchema(CreateCollectionReq.CollectionSchema.builder().fieldSchemaList(fields).build())
                .primaryFieldName("id")
                .vectorFieldName("embedding")
                .metricType("COSINE")
                .consistencyLevel(ConsistencyLevel.STRONG)
                .indexParams(List.of(vectorIndex))
                .description("Agent RAG Lite 文档块向量")
                .build());
    }

    public boolean exists() {
        return Boolean.TRUE.equals(client.hasCollection(HasCollectionReq.builder()
                .collectionName(collectionName).build()));
    }

    public void insert(List<VectorRecord> records) {
        List<JsonObject> rows = new ArrayList<>();
        for (VectorRecord record : records) {
            JsonObject row = new JsonObject();
            row.addProperty("id", record.id());
            row.addProperty("document_id", record.documentId());
            JsonArray embedding = new JsonArray();
            for (float value : record.embedding()) {
                embedding.add(value);
            }
            row.add("embedding", embedding);
            rows.add(row);
        }
        client.insert(InsertReq.builder().collectionName(collectionName).data(rows).build());
    }

    public void upsert(List<VectorRecord> records) {
        List<JsonObject> rows = rows(records);
        if (!rows.isEmpty()) client.upsert(UpsertReq.builder().collectionName(collectionName).data(rows).build());
    }

    public void deleteDocument(String documentId){
        client.delete(DeleteReq.builder().collectionName(collectionName)
                .filter("document_id == \"" + documentId + "\"").build());
    }

    public void deleteIds(List<String> ids){
        if(ids==null||ids.isEmpty())return;
        String values=ids.stream().map(id->"\""+id.replace("\"","\\\"")+"\"")
                .collect(java.util.stream.Collectors.joining(","));
        client.delete(DeleteReq.builder().collectionName(collectionName).filter("id in ["+values+"]").build());
    }

    public List<String> documentVectorIds(String documentId){
        return client.query(QueryReq.builder().collectionName(collectionName)
                        .filter("document_id == \"" + documentId + "\"")
                        .outputFields(List.of("id")).limit(10000).build())
                .getQueryResults().stream()
                .map(result->String.valueOf(result.getEntity().get("id"))).toList();
    }

    private List<JsonObject> rows(List<VectorRecord> records) {
        List<JsonObject> rows=new ArrayList<>();
        for(VectorRecord record:records){JsonObject row=new JsonObject();row.addProperty("id",record.id());row.addProperty("document_id",record.documentId());
            JsonArray values=new JsonArray();for(float value:record.embedding())values.add(value);row.add("embedding",values);rows.add(row);}return rows;
    }

    public List<VectorHit> search(float[] vector, int topK) {
        List<BaseVector> vectors = List.of(new FloatVec(vector));
        Map<String, Object> params = new HashMap<>();
        params.put("metric_type", "COSINE");
        params.put("ef", Math.max(64, topK));
        SearchResp response = client.search(SearchReq.builder()
                .collectionName(collectionName)
                .annsField("embedding")
                .data(vectors)
                .topK(topK)
                .searchParams(params)
                .outputFields(List.of("id", "document_id"))
                .build());
        if (response.getSearchResults() == null || response.getSearchResults().isEmpty()) {
            return List.of();
        }
        return response.getSearchResults().get(0).stream()
                .map(result -> new VectorHit(String.valueOf(result.getEntity().get("id")), result.getScore()))
                .toList();
    }

    public record VectorRecord(String id, String documentId, float[] embedding) {
    }

    public record VectorHit(String chunkId, float score) {
    }
}
