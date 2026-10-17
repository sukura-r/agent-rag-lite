package com.example.agentrag.agent;

import com.example.agentrag.model.OpenAiCompatibleProperties;
import com.example.agentrag.model.ModelCallRetrier;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.concurrent.CancellationException;
import java.util.regex.Pattern;

@Service
public class ToolCallingModel {

    private static final Pattern THINK_BLOCK = Pattern.compile("(?is)<think>.*?</think>");
    private static final Pattern LEAKED_DECISION = Pattern.compile(
            "(?is).*(?:knowledge_search|根据系统提示|系统提示中|工具调用过程|我需要先判断是否调用工具).*");

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final OpenAiCompatibleProperties properties;
    private final ModelCallRetrier retrier;

    public ToolCallingModel(RestClient modelRestClient,
                            ObjectMapper objectMapper,
                            OpenAiCompatibleProperties properties,
                            ModelCallRetrier retrier) {
        this.restClient = modelRestClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.retrier = retrier;
    }

    public ModelTurn complete(List<Map<String, Object>> messages, List<AgentTool> tools) {
        Map<String, Object> body = requestBody(messages, tools, false);

        String response = retrier.execute(() -> restClient.post()
                .uri(normalize(properties.baseUrl()) + "/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + properties.apiKey())
                .body(body)
                .retrieve()
                .body(String.class));
        try {
            JsonNode choice = objectMapper.readTree(response).path("choices").path(0);
            JsonNode message = choice.path("message");
            String content = message.path("content").isTextual() ? message.path("content").asText() : "";
            List<ToolCall> calls = new ArrayList<>();
            for (JsonNode call : message.path("tool_calls")) {
                String id = call.path("id").asText();
                String name = call.path("function").path("name").asText();
                String argumentsJson = call.path("function").path("arguments").asText("{}");
                Map<String, Object> arguments = objectMapper.readValue(
                        argumentsJson, new TypeReference<Map<String, Object>>() { });
                if (id.isBlank() || name.isBlank()) {
                    throw new IllegalStateException("工具调用响应缺少id或function.name");
                }
                calls.add(new ToolCall(id, name, arguments, argumentsJson));
            }
            return validatedTurn(content, calls, choice.path("finish_reason").asText("stop"));
        } catch (Exception exception) {
            if (exception instanceof IllegalStateException stateException) {
                throw stateException;
            }
            throw new IllegalStateException("解析Tool Calling响应失败", exception);
        }
    }

    public ModelTurn streamComplete(List<Map<String, Object>> messages, List<AgentTool> tools,
                                    Consumer<String> contentConsumer,
                                    BooleanSupplier cancellationRequested) {
        Map<String, Object> body = requestBody(messages, tools, true);
        return restClient.post()
                .uri(normalize(properties.baseUrl()) + "/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + properties.apiKey())
                .body(body)
                .exchange((request, response) -> {
                    if (response.getStatusCode().isError()) {
                        throw new IllegalStateException("流式模型调用失败，HTTP " + response.getStatusCode().value());
                    }
                    StringBuilder content = new StringBuilder();
                    Map<Integer, ToolCallAccumulator> calls = new HashMap<>();
                    String finishReason = "";
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                            response.getBody(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (Thread.currentThread().isInterrupted() || cancellationRequested.getAsBoolean()) {
                                throw new CancellationException("流式模型调用已取消");
                            }
                            if (!line.startsWith("data:")) {
                                continue;
                            }
                            String data = line.substring(5).trim();
                            if (data.isBlank() || data.equals("[DONE]")) {
                                continue;
                            }
                            JsonNode choice = objectMapper.readTree(data).path("choices").path(0);
                            JsonNode delta = choice.path("delta");
                            if (delta.path("content").isTextual()) {
                                String piece = delta.path("content").asText();
                                if (!piece.isEmpty()) {
                                    content.append(piece);
                                }
                            }
                            // reasoning字段永远是模型内部推理，不得写入SSE、数据库或最终答案。
                            if (choice.path("finish_reason").isTextual()) {
                                finishReason = choice.path("finish_reason").asText();
                            }
                            for (JsonNode call : delta.path("tool_calls")) {
                                int index = call.path("index").asInt(0);
                                ToolCallAccumulator accumulator = calls.computeIfAbsent(
                                        index, ignored -> new ToolCallAccumulator());
                                if (call.path("id").isTextual()) {
                                    accumulator.id.append(call.path("id").asText());
                                }
                                JsonNode function = call.path("function");
                                if (function.path("name").isTextual()) {
                                    accumulator.name.append(function.path("name").asText());
                                }
                                if (function.path("arguments").isTextual()) {
                                    accumulator.arguments.append(function.path("arguments").asText());
                                }
                            }
                        }
                    }
                    List<ToolCall> toolCalls = calls.entrySet().stream()
                            .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                            .map(entry -> toToolCall(entry.getValue()))
                            .toList();
                    ModelTurn turn = validatedTurn(content.toString(), toolCalls,
                            finishReason.isBlank() ? "stop" : finishReason);
                    // 工具决策过程中的文字全部丢弃；只有确认是完整最终答案后才按句发布。
                    if (turn.toolCalls().isEmpty()) {
                        emitSentences(turn.content(), contentConsumer);
                    }
                    return turn;
                });
    }

    private Map<String, Object> requestBody(List<Map<String, Object>> messages,
                                            List<AgentTool> tools, boolean stream) {
        List<Map<String, Object>> toolSchemas = tools.stream().map(tool -> Map.<String, Object>of(
                "type", "function",
                "function", Map.of(
                        "name", tool.name(),
                        "description", tool.description(),
                        "parameters", tool.parameters()))).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.chatModel());
        body.put("temperature", 0.1);
        if (!toolSchemas.isEmpty()) {
            body.put("tools", toolSchemas);
            body.put("tool_choice", "auto");
        }
        body.put("stream", stream);
        String reasoningEffort = properties.reasoningEffort();
        boolean localOllama = isLocalOllama();
        if (reasoningEffort.isBlank() && localOllama) {
            reasoningEffort = "none";
        }
        boolean noThinking = "none".equalsIgnoreCase(reasoningEffort);
        body.put("messages", localOllama && noThinking ? withNoThink(messages) : messages);
        if (!reasoningEffort.isBlank()) {
            body.put("reasoning_effort", reasoningEffort);
        }
        if (localOllama && noThinking) {
            body.put("think", false);
        }
        body.put("max_tokens", properties.maxOutputTokens());
        return body;
    }

    private List<Map<String, Object>> withNoThink(List<Map<String, Object>> messages) {
        List<Map<String, Object>> result = new ArrayList<>(messages.size());
        int lastUser = -1;
        for (int i = 0; i < messages.size(); i++) {
            Map<String, Object> copy = new LinkedHashMap<>(messages.get(i));
            result.add(copy);
            if ("user".equals(copy.get("role")) && copy.get("content") instanceof String) {
                lastUser = i;
            }
        }
        if (lastUser >= 0) {
            Map<String, Object> user = result.get(lastUser);
            String content = (String) user.get("content");
            if (!content.contains("/no_think")) {
                user.put("content", content + "\n/no_think");
            }
        }
        return result;
    }

    private boolean isLocalOllama() {
        return "ollama".equalsIgnoreCase(properties.apiKey())
                && properties.baseUrl() != null
                && properties.baseUrl().replaceAll("/+$", "").endsWith("/v1");
    }

    private ToolCall toToolCall(ToolCallAccumulator accumulator) {
        try {
            String id = accumulator.id.toString();
            String name = accumulator.name.toString();
            String argumentsJson = accumulator.arguments.isEmpty() ? "{}" : accumulator.arguments.toString();
            Map<String, Object> arguments = objectMapper.readValue(
                    argumentsJson, new TypeReference<Map<String, Object>>() { });
            if (id.isBlank() || name.isBlank()) {
                throw new IllegalStateException("流式工具调用缺少id或function.name");
            }
            return new ToolCall(id, name, arguments, argumentsJson);
        } catch (Exception exception) {
            if (exception instanceof IllegalStateException stateException) {
                throw stateException;
            }
            throw new IllegalStateException("解析流式工具arguments失败", exception);
        }
    }

    private String normalize(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private ModelTurn validatedTurn(String rawContent, List<ToolCall> calls, String finishReason) {
        if ("length".equalsIgnoreCase(finishReason)) {
            throw new ModelOutputTruncatedException("模型输出达到长度上限，未生成完整回答");
        }
        String content = THINK_BLOCK.matcher(rawContent == null ? "" : rawContent).replaceAll("").strip();
        if (content.contains("<think>") || content.contains("</think>")) {
            throw new IllegalStateException("模型返回了未闭合的内部推理内容");
        }
        if (!calls.isEmpty()) {
            return new ModelTurn("", calls, finishReason);
        }
        if (content.isBlank()) {
            throw new IllegalStateException("模型既没有返回最终答案，也没有返回工具调用");
        }
        if (LEAKED_DECISION.matcher(content).matches()) {
            throw new IllegalStateException("模型返回了内部工具决策，已拒绝作为最终答案");
        }
        return new ModelTurn(content, calls, finishReason);
    }

    private void emitSentences(String content, Consumer<String> consumer) {
        StringBuilder sentence = new StringBuilder();
        for (int i = 0; i < content.length(); i++) {
            char current = content.charAt(i);
            sentence.append(current);
            if (current == '。' || current == '！' || current == '？'
                    || current == '；' || current == '\n' || sentence.length() >= 120) {
                consumer.accept(sentence.toString());
                sentence.setLength(0);
            }
        }
        if (!sentence.isEmpty()) {
            consumer.accept(sentence.toString());
        }
    }

    public record ModelTurn(String content, List<ToolCall> toolCalls, String finishReason) {
        public ModelTurn(String content, List<ToolCall> toolCalls) {
            this(content, toolCalls, "stop");
        }
    }

    public static final class ModelOutputTruncatedException extends IllegalStateException {
        public ModelOutputTruncatedException(String message) {
            super(message);
        }
    }

    public record ToolCall(String id, String name, Map<String, Object> arguments, String argumentsJson) {
        public Map<String, Object> asAssistantToolCall() {
            return Map.of(
                    "id", id,
                    "type", "function",
                    "function", Map.of("name", name, "arguments", argumentsJson));
        }
    }

    private static final class ToolCallAccumulator {
        private final StringBuilder id = new StringBuilder();
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();
    }
}
