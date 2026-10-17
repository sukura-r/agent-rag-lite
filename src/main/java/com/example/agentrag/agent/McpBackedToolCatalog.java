package com.example.agentrag.agent;

import com.example.agentrag.mcp.McpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class McpBackedToolCatalog implements AgentToolCatalog {

    private static final Logger log = LoggerFactory.getLogger(McpBackedToolCatalog.class);

    private final McpClient mcpClient;
    private final Map<String, AgentTool> staticTools;
    private final boolean enabled;
    private final Set<String> allowlist;

    public McpBackedToolCatalog(McpClient mcpClient,
                                KnowledgeSearchTool staticKnowledgeSearch,
                                KnowledgeDocumentGetTool staticDocumentGet,
                                @Value("${app.mcp.enabled:true}") boolean enabled,
                                @Value("${app.mcp.allowed-tools:knowledge_search,knowledge_document_get}") List<String> allowedTools) {
        this.mcpClient = mcpClient;
        this.staticTools = List.<AgentTool>of(staticKnowledgeSearch, staticDocumentGet).stream()
                .collect(Collectors.toUnmodifiableMap(AgentTool::name, Function.identity()));
        this.enabled = enabled;
        this.allowlist = Set.copyOf(allowedTools);
    }

    @Override
    public ToolSet load() {
        if (!enabled) {
            return staticTools("static-disabled");
        }
        try {
            List<AgentTool> tools = mcpClient.listTools().stream()
                    .filter(definition -> allowlist.contains(definition.name()))
                    .map(definition -> (AgentTool) new McpAgentTool(
                            definition, mcpClient, staticTools.get(definition.name())))
                    .toList();
            if (tools.isEmpty()) {
                log.warn("MCP未发现任何白名单工具，降级到静态工具，allowlist={}", allowlist);
                return staticTools("static-empty-discovery-fallback");
            }
            log.info("MCP动态工具发现成功，tools={}", tools.stream().map(AgentTool::name).toList());
            return new ToolSet(tools, "mcp-dynamic");
        } catch (RuntimeException exception) {
            log.warn("MCP工具发现失败，降级到静态工具：{}", exception.getMessage());
            return staticTools("static-discovery-fallback");
        }
    }

    private ToolSet staticTools(String mode) {
        return new ToolSet(staticTools.values().stream()
                .filter(tool -> allowlist.contains(tool.name()))
                .sorted(java.util.Comparator.comparing(AgentTool::name))
                .toList(), mode);
    }
}
