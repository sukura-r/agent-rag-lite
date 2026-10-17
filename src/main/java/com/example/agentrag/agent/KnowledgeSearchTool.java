package com.example.agentrag.agent;

import com.example.agentrag.knowledge.KnowledgeService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class KnowledgeSearchTool implements AgentTool {

    private final KnowledgeService knowledgeService;

    public KnowledgeSearchTool(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @Override
    public String name() {
        return "knowledge_search";
    }

    @Override
    public String description() {
        return "搜索企业内部知识库。用户询问公司制度、流程、规定、内部政策或其他企业私有事实时必须使用；"
                + "不要用于普通问候、仅改写用户已提供文本或查询企业知识库之外的公开实时信息。";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> query = Map.of(
                "type", "string",
                "description", "根据用户问题提炼出的知识库检索语句");
        Map<String, Object> topK = Map.of(
                "type", "integer",
                "description", "需要返回的候选片段数量，范围1到5",
                "minimum", 1,
                "maximum", 5);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("query", query, "topK", topK));
        schema.put("required", List.of("query"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        String query = arguments.get("query") instanceof String value ? value.trim() : "";
        if (query.isBlank()) {
            throw new IllegalArgumentException("knowledge_search.query不能为空");
        }
        int topK = 3;
        Object rawTopK = arguments.get("topK");
        if (rawTopK instanceof Number number) {
            topK = Math.max(1, Math.min(5, number.intValue()));
        }
        List<KnowledgeService.SearchResult> evidence = knowledgeService.search(query, topK);
        if (evidence.isEmpty()) {
            return new ToolResult("知识库没有检索到相关证据。", List.of());
        }
        StringBuilder observation = new StringBuilder("以下是知识库检索结果，只能依据这些证据回答：\n\n");
        for (int i = 0; i < evidence.size(); i++) {
            KnowledgeService.SearchResult item = evidence.get(i);
            observation.append("[证据").append(i + 1).append("]\n")
                    .append("标题：").append(item.title()).append("\n")
                    .append("Document ID：").append(item.documentId()).append("\n")
                    .append("Chunk ID：").append(item.chunkId()).append("\n")
                    .append("内容：").append(item.content()).append("\n\n");
        }
        return new ToolResult(observation.toString(), evidence);
    }
}
