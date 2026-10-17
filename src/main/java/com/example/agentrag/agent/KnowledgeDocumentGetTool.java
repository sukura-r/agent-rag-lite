package com.example.agentrag.agent;

import com.example.agentrag.knowledge.KnowledgeService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class KnowledgeDocumentGetTool implements AgentTool {

    private static final int MAX_CHUNKS = 20;

    private final KnowledgeService knowledgeService;

    public KnowledgeDocumentGetTool(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @Override
    public String name() {
        return "knowledge_document_get";
    }

    @Override
    public String description() {
        return "根据knowledge_search证据中返回的准确documentId读取同一份企业知识文档的全部Chunk。"
                + "仅当用户需要总结整份制度、列出完整流程或综合多个条款，并且已经通过knowledge_search获得documentId时使用；"
                + "不能猜测或编造documentId，也不要用它代替普通语义检索。";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> documentId = Map.of(
                "type", "string",
                "description", "必须来自knowledge_search结果的32位文档ID",
                "pattern", "^[0-9a-fA-F]{32}$");
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("documentId", documentId));
        schema.put("required", List.of("documentId"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        String documentId = arguments.get("documentId") instanceof String value ? value.trim() : "";
        if (!documentId.matches("^[0-9a-fA-F]{32}$")) {
            throw new IllegalArgumentException("knowledge_document_get.documentId必须是knowledge_search返回的32位文档ID");
        }
        List<KnowledgeService.SearchResult> chunks = knowledgeService.getReadyDocumentChunks(documentId);
        if (chunks.isEmpty()) {
            return new ToolResult("没有找到READY状态的目标文档，请重新使用knowledge_search确认documentId。", List.of());
        }
        if (chunks.size() > MAX_CHUNKS) {
            throw new IllegalArgumentException("目标文档包含" + chunks.size()
                    + "个Chunk，超过单次读取上限" + MAX_CHUNKS + "，请改用knowledge_search缩小问题范围");
        }
        StringBuilder observation = new StringBuilder()
                .append("已读取完整文档：").append(chunks.get(0).title()).append("\n")
                .append("Document ID：").append(documentId).append("\n")
                .append("Chunk总数：").append(chunks.size()).append("\n\n");
        for (KnowledgeService.SearchResult chunk : chunks) {
            observation.append("[文档Chunk ").append(chunk.chunkIndex()).append("]\n")
                    .append("Chunk ID：").append(chunk.chunkId()).append("\n")
                    .append("内容：").append(chunk.content()).append("\n\n");
        }
        return new ToolResult(observation.toString(), chunks);
    }
}
