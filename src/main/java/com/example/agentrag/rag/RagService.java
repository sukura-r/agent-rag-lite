package com.example.agentrag.rag;

import com.example.agentrag.knowledge.KnowledgeService;
import com.example.agentrag.model.ChatModel;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RagService {

    private final KnowledgeService knowledgeService;
    private final RagPromptBuilder promptBuilder;
    private final ChatModel chatModel;

    public RagService(KnowledgeService knowledgeService, RagPromptBuilder promptBuilder, ChatModel chatModel) {
        this.knowledgeService = knowledgeService;
        this.promptBuilder = promptBuilder;
        this.chatModel = chatModel;
    }

    public RagAnswer ask(String question, int topK) {
        List<KnowledgeService.SearchResult> evidence = knowledgeService.search(question, topK);
        if (evidence.isEmpty()) {
            return new RagAnswer("知识库中没有检索到证据，我暂时无法回答。", List.of(), chatModel.mode());
        }
        String answer = chatModel.answer(promptBuilder.systemPrompt(), promptBuilder.userPrompt(question, evidence));
        List<Citation> citations = java.util.stream.IntStream.range(0, evidence.size())
                .mapToObj(index -> {
                    KnowledgeService.SearchResult item = evidence.get(index);
                    return new Citation(index + 1, item.chunkId(), item.documentId(), item.title(),
                            item.chunkIndex(), item.content(), item.score());
                }).toList();
        return new RagAnswer(answer, citations, chatModel.mode());
    }

    public record RagAnswer(String answer, List<Citation> citations, String modelMode) {
    }

    public record Citation(int number, String chunkId, String documentId, String title,
                           int chunkIndex, String content, float retrievalScore) {
    }
}
