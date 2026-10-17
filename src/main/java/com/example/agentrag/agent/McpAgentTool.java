package com.example.agentrag.agent;

import com.example.agentrag.mcp.McpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

final class McpAgentTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(McpAgentTool.class);

    private final McpClient.ToolDefinition definition;
    private final McpClient client;
    private final AgentTool fallback;

    McpAgentTool(McpClient.ToolDefinition definition, McpClient client, AgentTool fallback) {
        this.definition = definition;
        this.client = client;
        this.fallback = fallback;
    }

    @Override
    public String name() {
        return definition.name();
    }

    @Override
    public String description() {
        return definition.description();
    }

    @Override
    public Map<String, Object> parameters() {
        return definition.inputSchema();
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        try {
            return client.callTool(name(), arguments);
        } catch (RuntimeException exception) {
            if (fallback != null && fallback.name().equals(name())) {
                log.warn("MCP工具调用失败，使用同名静态工具降级，tool={}, reason={}", name(), exception.getMessage());
                return fallback.execute(arguments);
            }
            throw exception;
        }
    }
}
