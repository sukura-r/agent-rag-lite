package com.example.agentrag.model;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.model")
public record OpenAiCompatibleProperties(
        String baseUrl,
        String apiKey,
        String chatModel,
        String embeddingModel,
        int timeoutSeconds,
        String reasoningEffort,
        int maxOutputTokens) {

    public OpenAiCompatibleProperties {
        reasoningEffort = reasoningEffort == null ? "" : reasoningEffort.strip();
        maxOutputTokens = maxOutputTokens <= 0 ? 512 : maxOutputTokens;
    }
}
