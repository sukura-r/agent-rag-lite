package com.example.agentrag.retrieval;

import com.example.agentrag.model.ChatModel;
import com.example.agentrag.model.OpenAiCompatibleProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class LlmReranker {

    private static final Logger log = LoggerFactory.getLogger(LlmReranker.class);
    private static final Pattern ARRAY = Pattern.compile("\\[(?:\\s*\\d+\\s*,?\\s*)+]", Pattern.DOTALL);
    private static final int MAX_CANDIDATE_CONTENT_CHARS = 300;
    private final ChatModel chatModel;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final OpenAiCompatibleProperties properties;
    private final AtomicLong fallbackCount = new AtomicLong();

    public LlmReranker(ChatModel chatModel) {
        this(chatModel, null, null, null);
    }

    @Autowired
    public LlmReranker(ChatModel chatModel,
                       RestClient modelRestClient,
                       ObjectMapper objectMapper,
                       OpenAiCompatibleProperties properties) {
        this.chatModel = chatModel;
        this.restClient = modelRestClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public List<RetrievalCandidate> rerank(String query, List<RetrievalCandidate> candidates) {
        if (candidates.size() <= 1) {
            return candidates;
        }
        StringBuilder prompt = new StringBuilder("问题：").append(query).append("\n\n候选片段：\n");
        for (int i = 0; i < candidates.size(); i++) {
            RetrievalCandidate item = candidates.get(i);
            prompt.append(i + 1).append(". 标题：").append(item.chunk().title())
                    .append("\n内容：").append(limitContent(item.chunk().content())).append("\n\n");
        }
        prompt.append("请按与问题的相关性从高到低排列全部候选编号。只输出JSON整数数组，例如[2,1,3]，不要解释。 /no_think");
        try {
            String output = isLocalOllama()
                    ? answerWithOllamaStructuredOutput(prompt.toString(), candidates.size())
                    : chatModel.answer("你是企业知识检索精排器。只根据候选文本与问题的相关性排序，不回答问题。", prompt.toString());
            List<Integer> order = parseOrder(output, candidates.size());
            if (order.isEmpty()) {
                return candidates;
            }
            List<RetrievalCandidate> ranked = new ArrayList<>();
            Set<Integer> used = new HashSet<>();
            for (int index : order) {
                if (index >= 1 && index <= candidates.size() && used.add(index)) {
                    ranked.add(candidates.get(index - 1).withRerankScore(1.0 / (ranked.size() + 1.0)));
                }
            }
            for (int i = 1; i <= candidates.size(); i++) {
                if (used.add(i)) {
                    ranked.add(candidates.get(i - 1).withRerankScore(1.0 / (ranked.size() + 1.0)));
                }
            }
            return ranked;
        } catch (RuntimeException exception) {
            fallbackCount.incrementAndGet();
            log.warn("LLM Rerank失败，降级为RRF顺序: {}", exception.getMessage());
            return candidates;
        }
    }

    public long fallbackCount() {
        return fallbackCount.get();
    }

    private String limitContent(String content) {
        if (content == null || content.length() <= MAX_CANDIDATE_CONTENT_CHARS) {
            return content == null ? "" : content;
        }
        return content.substring(0, MAX_CANDIDATE_CONTENT_CHARS) + "……";
    }

    private boolean isLocalOllama() {
        return properties != null && restClient != null && objectMapper != null
                && "ollama".equalsIgnoreCase(properties.apiKey())
                && properties.baseUrl() != null && properties.baseUrl().endsWith("/v1");
    }

    private String answerWithOllamaStructuredOutput(String prompt, int candidateCount) {
        Map<String, Object> integerItems = Map.of("type", "integer", "minimum", 1, "maximum", candidateCount);
        Map<String, Object> schema = Map.of(
                "type", "array",
                "items", integerItems,
                "minItems", candidateCount,
                "maxItems", candidateCount);
        Map<String, Object> body = Map.of(
                "model", properties.chatModel(),
                "stream", false,
                "think", false,
                "format", schema,
                "options", Map.of("temperature", 0, "num_predict", candidateCount * 8),
                "messages", List.of(
                        Map.of("role", "system", "content", "你是企业知识检索精排器，只输出候选编号JSON数组。"),
                        Map.of("role", "user", "content", prompt)));
        String baseUrl = properties.baseUrl().substring(0, properties.baseUrl().length() - 3);
        String response = restClient.post()
                .uri(baseUrl + "/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
        try {
            JsonNode content = objectMapper.readTree(response).path("message").path("content");
            if (!content.isTextual() || content.asText().isBlank()) {
                throw new IllegalStateException("Ollama精排响应缺少message.content");
            }
            return content.asText();
        } catch (Exception exception) {
            if (exception instanceof IllegalStateException stateException) {
                throw stateException;
            }
            throw new IllegalStateException("解析Ollama精排响应失败", exception);
        }
    }

    List<Integer> parseOrder(String output, int candidateCount) {
        Matcher matcher = ARRAY.matcher(output == null ? "" : output);
        if (!matcher.find()) {
            return List.of();
        }
        List<Integer> order = new ArrayList<>();
        Matcher numbers = Pattern.compile("\\d+").matcher(matcher.group());
        while (numbers.find()) {
            int value = Integer.parseInt(numbers.group());
            if (value >= 1 && value <= candidateCount) {
                order.add(value);
            }
        }
        return order;
    }
}
