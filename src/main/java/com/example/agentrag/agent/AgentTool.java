package com.example.agentrag.agent;

import com.example.agentrag.knowledge.KnowledgeService;

import java.util.List;
import java.util.Map;

public interface AgentTool {

    String name();

    String description();

    Map<String, Object> parameters();

    ToolResult execute(Map<String, Object> arguments);

    record ToolResult(String observation, List<KnowledgeService.SearchResult> evidence) {
    }
}
