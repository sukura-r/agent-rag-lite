package com.example.agentrag.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class PublicBenchmarkDatasetService {

    private static final int QUERY_LIMIT = 300;
    private static final int CORPUS_LIMIT = 5000;
    private final ObjectMapper objectMapper;

    public PublicBenchmarkDatasetService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Dataset load() {
        JsonNode querySource = read("evaluation/public-duretrieval-queries-source.json");
        JsonNode corpusSource = read("evaluation/public-duretrieval-corpus-source.json");
        JsonNode qrelsSource = read("evaluation/public-duretrieval-qrels-source.json");
        JsonNode metadata = read("evaluation/public-duretrieval-metadata.json");

        List<Query> queries = new ArrayList<>();
        Set<String> selectedQueryIds = new LinkedHashSet<>();
        for (JsonNode item : querySource.path("rows")) {
            if (queries.size() == QUERY_LIMIT) break;
            JsonNode row = item.path("row");
            Query query = new Query(row.path("id").asText(), row.path("text").asText());
            queries.add(query);
            selectedQueryIds.add(query.id());
        }

        List<Document> documents = new ArrayList<>();
        Set<String> documentIds = new LinkedHashSet<>();
        for (JsonNode item : corpusSource.path("rows")) {
            if (documents.size() == CORPUS_LIMIT) break;
            JsonNode row = item.path("row");
            Document document = new Document(row.path("id").asText(), row.path("text").asText());
            documents.add(document);
            documentIds.add(document.id());
        }

        Map<String, Set<String>> relevantByQuery = new LinkedHashMap<>();
        selectedQueryIds.forEach(id -> relevantByQuery.put(id, new LinkedHashSet<>()));
        for (JsonNode item : qrelsSource.path("rows")) {
            JsonNode row = item.path("row");
            String queryId = row.path("qid").asText();
            String documentId = row.path("pid").asText();
            if (selectedQueryIds.contains(queryId) && row.path("score").asInt() > 0 && documentIds.contains(documentId)) {
                relevantByQuery.get(queryId).add(documentId);
            }
        }

        validate(queries, documents, relevantByQuery, metadata);
        return new Dataset(metadata.path("dataset").asText(), metadata.path("datasetRevision").asText(),
                metadata.path("qrelsRevision").asText(), metadata.path("paperUrl").asText(),
                metadata.path("selection").asText(), metadata.path("licenseNote").asText(),
                List.copyOf(queries), List.copyOf(documents), immutableSets(relevantByQuery));
    }

    private void validate(List<Query> queries, List<Document> documents, Map<String, Set<String>> qrels,
                          JsonNode metadata) {
        if (queries.size() != metadata.path("queryCount").asInt()) {
            throw new IllegalStateException("公开基准查询数量与元数据不一致");
        }
        if (documents.size() != metadata.path("corpusCount").asInt()) {
            throw new IllegalStateException("公开基准候选文本数量与元数据不一致");
        }
        List<String> missing = queries.stream().map(Query::id).filter(id -> qrels.get(id).isEmpty()).toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("公开基准子集缺少官方相关性标注：" + missing);
        }
        if (queries.stream().map(Query::id).distinct().count() != queries.size()
                || documents.stream().map(Document::id).distinct().count() != documents.size()) {
            throw new IllegalStateException("公开基准存在重复ID");
        }
    }

    private Map<String, Set<String>> immutableSets(Map<String, Set<String>> source) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, Set.copyOf(value)));
        return Map.copyOf(result);
    }

    private JsonNode read(String path) {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return objectMapper.readTree(input);
        } catch (IOException exception) {
            throw new IllegalStateException("评测资源不存在或无法读取：" + path, exception);
        }
    }

    public record Dataset(String name, String datasetRevision, String qrelsRevision, String paperUrl,
                          String selection, String licenseNote, List<Query> queries, List<Document> documents,
                          Map<String, Set<String>> relevantDocumentIds) { }
    public record Query(String id, String text) { }
    public record Document(String id, String text) { }
}
