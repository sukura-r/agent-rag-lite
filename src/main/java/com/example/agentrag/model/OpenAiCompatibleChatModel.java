package com.example.agentrag.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Service
public class OpenAiCompatibleChatModel implements ChatModel {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final OpenAiCompatibleProperties properties;
    private final ModelCallRetrier retrier;

    public OpenAiCompatibleChatModel(RestClient modelRestClient,
                                     ObjectMapper objectMapper,
                                     OpenAiCompatibleProperties properties,
                                     ModelCallRetrier retrier) {
        this.restClient = modelRestClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.retrier = retrier;
        require(properties.baseUrl(), "MODEL_BASE_URL");
        require(properties.apiKey(), "MODEL_API_KEY");
        require(properties.chatModel(), "CHAT_MODEL");
    }

    @Override
    public String answer(String systemPrompt, String userPrompt) {
        Map<String, Object> body = Map.of(
                "model", properties.chatModel(),
                "temperature", 0.1,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)));
        return retrier.execute(() -> requestAndParse(body));
    }

    private String requestAndParse(Map<String, Object> body) {
        String response = restClient.post()
                .uri(normalize(properties.baseUrl()) + "/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + properties.apiKey())
                .body(body)
                .retrieve()
                .body(String.class);
        try {
            JsonNode content = objectMapper.readTree(response)
                    .path("choices").path(0).path("message").path("content");
            if (!content.isTextual() || content.asText().isBlank()) {
                throw new IllegalStateException("Chat 响应缺少 choices[0].message.content");
            }
            return content.asText();
        } catch (Exception exception) {
            if (exception instanceof IllegalStateException stateException) {
                throw stateException;
            }
            throw new IllegalStateException("解析 Chat 响应失败", exception);
        }
    }

    @Override
    public String mode() {
        return "openai-compatible-llm";
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
