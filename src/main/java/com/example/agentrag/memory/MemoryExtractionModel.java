package com.example.agentrag.memory;

import com.example.agentrag.model.OpenAiCompatibleProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

/** Optional enrichment has a short, separate request budget and no retry storm. */
@Component
public class MemoryExtractionModel {
    private final OpenAiCompatibleProperties properties;
    private final ObjectMapper json;
    private final Duration timeout;
    private final HttpClient client;

    public MemoryExtractionModel(OpenAiCompatibleProperties properties, ObjectMapper json,
                                 @Value("${app.memory.extraction-timeout-ms:5000}") int timeoutMillis) {
        this.properties = properties; this.json = json;
        this.timeout = Duration.ofMillis(Math.max(100, Math.min(15000, timeoutMillis)));
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    public String extract(String instruction, String message) {
        try {
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("model", properties.chatModel()); body.put("temperature", 0);
            body.put("max_tokens", 512);
            if (!properties.reasoningEffort().isBlank()) body.put("reasoning_effort", properties.reasoningEffort());
            body.put("messages", List.of(Map.of("role", "system", "content", instruction),
                    Map.of("role", "user", "content", message)));
            String base = properties.baseUrl().replaceAll("/+$", "");
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/chat/completions"))
                    .timeout(timeout).header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + properties.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300 || response.body().length() > 20000) {
                throw new IllegalStateException("Memory抽取响应无效");
            }
            var result = json.readTree(response.body());
            if (!"stop".equals(result.path("choices").path(0).path("finish_reason").asText())) {
                throw new IllegalStateException("Memory抽取没有完整结束");
            }
            var content = result.path("choices").path(0).path("message").path("content");
            if (!content.isTextual()) throw new IllegalStateException("Memory抽取缺少正文");
            return content.asText();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Memory抽取已取消");
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (Exception failure) {
            throw new IllegalStateException("Memory抽取调用失败", failure);
        }
    }
}
