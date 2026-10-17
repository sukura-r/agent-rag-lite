package com.example.agentrag.embedding;

import com.example.agentrag.model.OpenAiCompatibleProperties;
import com.example.agentrag.model.ModelCallRetrier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Service
public class OpenAiCompatibleEmbeddingService implements EmbeddingService {

    private static final int BATCH_SIZE = 32;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final OpenAiCompatibleProperties properties;
    private final int dimension;
    private final ModelCallRetrier retrier;

    public OpenAiCompatibleEmbeddingService(RestClient modelRestClient,
                                             ObjectMapper objectMapper,
                                             OpenAiCompatibleProperties properties,
                                             org.springframework.core.env.Environment environment,
                                             ModelCallRetrier retrier) {
        this.restClient = modelRestClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.dimension = environment.getProperty("app.milvus.dimension", Integer.class, 128);
        this.retrier = retrier;
        require(properties.baseUrl(), "MODEL_BASE_URL");
        require(properties.apiKey(), "MODEL_API_KEY");
        require(properties.embeddingModel(), "EMBEDDING_MODEL");
    }

    @Override
    public float[] embed(String text) {
        return embedBatch(List.of(text)).get(0);
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        java.util.ArrayList<float[]> result = new java.util.ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += BATCH_SIZE) {
            result.addAll(embedBatch(texts.subList(start, Math.min(start + BATCH_SIZE, texts.size()))));
        }
        return List.copyOf(result);
    }

    private List<float[]> embedBatch(List<String> texts) {
        Object input = texts.size() == 1 ? texts.get(0) : texts;
        String response = retrier.execute(() -> restClient.post()
                .uri(normalize(properties.baseUrl()) + "/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + properties.apiKey())
                .body(Map.of("model", properties.embeddingModel(), "input", input))
                .retrieve()
                .body(String.class));
        try {
            JsonNode data = objectMapper.readTree(response).path("data");
            if (!data.isArray() || data.size() != texts.size()) {
                throw new IllegalStateException("Embedding 响应数量与请求文本数量不一致");
            }
            float[][] ordered = new float[texts.size()][];
            for (int itemIndex = 0; itemIndex < data.size(); itemIndex++) {
                JsonNode item = data.get(itemIndex);
                JsonNode embedding = item.path("embedding");
                if (!embedding.isArray()) {
                    throw new IllegalStateException("Embedding 响应缺少向量数组");
                }
                float[] vector = new float[embedding.size()];
                for (int i = 0; i < embedding.size(); i++) {
                    vector[i] = embedding.get(i).floatValue();
                }
                validateAndNormalize(vector);
                int responseIndex = item.has("index") ? item.path("index").asInt(itemIndex) : itemIndex;
                if (responseIndex < 0 || responseIndex >= ordered.length || ordered[responseIndex] != null) {
                    throw new IllegalStateException("Embedding 响应中的index无效");
                }
                ordered[responseIndex] = vector;
            }
            java.util.ArrayList<float[]> result = new java.util.ArrayList<>(ordered.length);
            for (float[] vector : ordered) {
                if (vector == null) {
                    throw new IllegalStateException("Embedding 响应缺少部分输入的向量");
                }
                result.add(vector);
            }
            return List.copyOf(result);
        } catch (Exception exception) {
            if (exception instanceof IllegalStateException stateException) {
                throw stateException;
            }
            throw new IllegalStateException("解析 Embedding 响应失败", exception);
        }
    }

    private void validateAndNormalize(float[] vector) {
        if (vector.length != dimension) {
            throw new IllegalStateException("模型实际维度 " + vector.length
                    + " 与 app.milvus.dimension=" + dimension + " 不一致");
        }
        normalizeVector(vector);
    }

    @Override
    public int dimension() {
        return dimension;
    }

    private void normalizeVector(float[] vector) {
        double sum = 0;
        for (float value : vector) {
            sum += value * value;
        }
        double length = Math.sqrt(sum);
        if (length == 0) {
            throw new IllegalStateException("Embedding 模型返回了零向量");
        }
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) (vector[i] / length);
        }
    }

    private String normalize(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private void require(String value, String environmentName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("remote 模式必须配置 " + environmentName);
        }
    }
}
