package com.example.agentrag.mcp;

import com.example.agentrag.agent.AgentTool;
import com.example.agentrag.knowledge.KnowledgeService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class McpClient {

    private static final String PROTOCOL_VERSION = "2025-06-18";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final AtomicLong requestIds = new AtomicLong();

    @Autowired
    public McpClient(RestClient.Builder builder,
                     ObjectMapper objectMapper,
                     @Value("${app.mcp.server-url:http://127.0.0.1:8080/mcp}") String serverUrl,
                     @Value("${app.mcp.timeout-ms:3000}") int timeoutMs,
                     @Value("${app.security.mcp-token}") String mcpToken) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeoutMs);
        requestFactory.setReadTimeout(timeoutMs);
        this.restClient = builder.requestFactory(requestFactory).baseUrl(serverUrl)
                .defaultHeader("X-MCP-Token", mcpToken).build();
        this.objectMapper = objectMapper;
    }

    McpClient(RestClient restClient, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    public List<ToolDefinition> listTools() {
        initialize();
        Map<String, Object> result = request("tools/list", Map.of());
        Object rawTools = result.get("tools");
        if (!(rawTools instanceof List<?> tools)) {
            throw new IllegalStateException("MCP tools/list响应缺少tools数组");
        }
        List<ToolDefinition> definitions = new ArrayList<>();
        for (Object item : tools) {
            Map<String, Object> tool = asMap(item);
            String name = requiredString(tool, "name");
            String description = requiredString(tool, "description");
            Map<String, Object> inputSchema = asMap(tool.get("inputSchema"));
            definitions.add(new ToolDefinition(name, description, inputSchema));
        }
        return definitions;
    }

    public AgentTool.ToolResult callTool(String name, Map<String, Object> arguments) {
        Map<String, Object> result = request("tools/call", Map.of("name", name, "arguments", arguments));
        if (Boolean.TRUE.equals(result.get("isError"))) {
            throw new IllegalArgumentException(extractText(result));
        }
        Map<String, Object> structured = asMap(result.get("structuredContent"));
        String observation = structured.get("observation") instanceof String value ? value : extractText(result);
        List<KnowledgeService.SearchResult> evidence = objectMapper.convertValue(
                structured.getOrDefault("evidence", List.of()), new TypeReference<>() { });
        return new AgentTool.ToolResult(observation, evidence);
    }

    private void initialize() {
        request("initialize", Map.of(
                "protocolVersion", PROTOCOL_VERSION,
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "agent-rag-lite", "version", "0.0.1")));
    }

    private Map<String, Object> request(String method, Map<String, Object> params) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", requestIds.incrementAndGet());
        body.put("method", method);
        body.put("params", params);
        Map<?, ?> response = restClient.post()
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);
        if (response == null) {
            throw new IllegalStateException("MCP服务器返回空响应");
        }
        if (response.get("error") != null) {
            throw new IllegalStateException("MCP调用失败：" + response.get("error"));
        }
        return asMap(response.get("result"));
    }

    private String extractText(Map<String, Object> result) {
        Object rawContent = result.get("content");
        if (rawContent instanceof List<?> contents) {
            for (Object item : contents) {
                Map<String, Object> content = asMap(item);
                if ("text".equals(content.get("type")) && content.get("text") instanceof String text) {
                    return text;
                }
            }
        }
        return "MCP工具没有返回文本结果";
    }

    private Map<String, Object> asMap(Object value) {
        if (value == null) {
            return Map.of();
        }
        return objectMapper.convertValue(value, new TypeReference<>() { });
    }

    private String requiredString(Map<String, Object> source, String key) {
        if (source.get(key) instanceof String value && !value.isBlank()) {
            return value;
        }
        throw new IllegalStateException("MCP响应字段缺失：" + key);
    }

    public record ToolDefinition(String name, String description, Map<String, Object> inputSchema) {
    }
}
