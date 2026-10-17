package com.example.agentrag.mcp;

import com.example.agentrag.agent.AgentTool;
import com.example.agentrag.agent.KnowledgeSearchTool;
import com.example.agentrag.agent.KnowledgeDocumentGetTool;
import com.example.agentrag.knowledge.KnowledgeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class McpServerController {

    private static final Logger log = LoggerFactory.getLogger(McpServerController.class);
    private static final String PROTOCOL_VERSION = "2025-06-18";

    private final Map<String, AgentTool> allowedTools;

    public McpServerController(KnowledgeSearchTool knowledgeSearchTool,
                               KnowledgeDocumentGetTool documentGetTool) {
        this.allowedTools = Map.of(
                knowledgeSearchTool.name(), knowledgeSearchTool,
                documentGetTool.name(), documentGetTool);
    }

    @PostMapping("/mcp")
    public ResponseEntity<Map<String, Object>> handle(@RequestBody Map<String, Object> request) {
        Object id = request.get("id");
        String method = request.get("method") instanceof String value ? value : "";
        Map<String, Object> params = request.get("params") instanceof Map<?, ?> raw
                ? copyMap(raw) : Map.of();
        try {
            Object result = switch (method) {
                case "initialize" -> initialize();
                case "tools/list" -> listTools();
                case "tools/call" -> callTool(params);
                default -> throw new McpProtocolException(-32601, "不支持的MCP方法：" + method);
            };
            return ResponseEntity.ok(success(id, result));
        } catch (McpProtocolException exception) {
            return ResponseEntity.ok(error(id, exception.code(), exception.getMessage()));
        } catch (IllegalArgumentException exception) {
            log.warn("MCP工具参数校验失败，method={}, reason={}", method, exception.getMessage());
            return ResponseEntity.ok(success(id, toolError(exception.getMessage())));
        } catch (RuntimeException exception) {
            log.error("MCP请求执行失败，method={}", method, exception);
            return ResponseEntity.ok(error(id, -32603, "MCP服务器内部错误"));
        }
    }

    private Map<String, Object> initialize() {
        return Map.of(
                "protocolVersion", PROTOCOL_VERSION,
                "capabilities", Map.of("tools", Map.of("listChanged", false)),
                "serverInfo", Map.of("name", "agent-rag-lite-mcp-server", "version", "0.0.1"));
    }

    private Map<String, Object> listTools() {
        List<Map<String, Object>> tools = allowedTools.values().stream().map(tool -> Map.<String, Object>of(
                "name", tool.name(),
                "description", tool.description(),
                "inputSchema", tool.parameters())).toList();
        return Map.of("tools", tools);
    }

    private Map<String, Object> callTool(Map<String, Object> params) {
        String name = params.get("name") instanceof String value ? value : "";
        AgentTool tool = allowedTools.get(name);
        if (tool == null) {
            throw new McpProtocolException(-32602, "工具不存在或未授权：" + name);
        }
        Map<String, Object> arguments = params.get("arguments") instanceof Map<?, ?> raw
                ? copyMap(raw) : Map.of();
        long start = System.nanoTime();
        AgentTool.ToolResult result = tool.execute(arguments);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        log.info("MCP工具调用完成，tool={}, elapsedMs={}, evidenceCount={}", name, elapsedMs, result.evidence().size());
        return Map.of(
                "content", List.of(Map.of("type", "text", "text", result.observation())),
                "structuredContent", Map.of(
                        "observation", result.observation(),
                        "evidence", result.evidence().stream().map(this::evidenceMap).toList()),
                "isError", false);
    }

    private Map<String, Object> evidenceMap(KnowledgeService.SearchResult item) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("chunkId", item.chunkId());
        map.put("documentId", item.documentId());
        map.put("title", item.title());
        map.put("chunkIndex", item.chunkIndex());
        map.put("content", item.content());
        map.put("score", item.score());
        return map;
    }

    private Map<String, Object> toolError(String message) {
        return Map.of(
                "content", List.of(Map.of("type", "text", "text", message)),
                "isError", true);
    }

    private Map<String, Object> success(Object id, Object result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);
        return response;
    }

    private Map<String, Object> error(Object id, int code, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", Map.of("code", code, "message", message));
        return response;
    }

    private Map<String, Object> copyMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private static final class McpProtocolException extends RuntimeException {
        private final int code;

        private McpProtocolException(int code, String message) {
            super(message);
            this.code = code;
        }

        private int code() {
            return code;
        }
    }
}
