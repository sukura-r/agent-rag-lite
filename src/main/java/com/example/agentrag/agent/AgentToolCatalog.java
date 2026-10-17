package com.example.agentrag.agent;

import java.util.List;

public interface AgentToolCatalog {

    ToolSet load();

    record ToolSet(List<AgentTool> tools, String mode) {
        public ToolSet {
            tools = List.copyOf(tools);
        }
    }
}
