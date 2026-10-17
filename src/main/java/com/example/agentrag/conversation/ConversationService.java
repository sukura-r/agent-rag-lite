package com.example.agentrag.conversation;

import com.example.agentrag.agent.AgentService;
import com.example.agentrag.memory.MemoryService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class ConversationService {

    private final ConversationRepository repository;
    private final MemoryService memoryService;
    private final int historyLimit;

    public ConversationService(ConversationRepository repository, MemoryService memoryService,
                               @Value("${app.agent.history-limit:10}") int historyLimit) {
        this.repository = repository;
        this.memoryService = memoryService;
        this.historyLimit = Math.max(0, historyLimit);
    }

    @Transactional
    public String create(String userId, String firstMessage) {
        String id = id();
        String normalized = firstMessage == null ? "新会话" : firstMessage.strip();
        String title = normalized.isBlank() ? "新会话"
                : normalized.substring(0, Math.min(50, normalized.length()));
        repository.createSession(id, userId, title);
        return id;
    }

    public void requireSession(String userId, String sessionId) {
        if (!repository.existsSession(userId, sessionId)) {
            throw new IllegalArgumentException("会话不存在或无权访问：" + sessionId);
        }
    }

    @Transactional
    public ConversationRepository.MessageRecord append(String userId, String sessionId, String role, String content) {
        requireSession(userId, sessionId);
        int sequence = repository.lockAndGetNextSequence(sessionId);
        String messageId = id();
        repository.insertMessage(messageId, sessionId, sequence, role, content);
        repository.touchSession(sessionId);
        return new ConversationRepository.MessageRecord(messageId, sessionId, sequence, role, content, null);
    }

    public List<ConversationRepository.MessageRecord> messages(String userId, String sessionId) {
        requireSession(userId, sessionId);
        return repository.findMessages(sessionId);
    }

    public List<AgentService.HistoryMessage> context(String userId, String sessionId, String question) {
        requireSession(userId, sessionId);
        List<AgentService.HistoryMessage> context = new java.util.ArrayList<>();
        List<ConversationRepository.MessageRecord> old = repository.findMessagesBeforeRecentWindow(sessionId, historyLimit);
        if (!old.isEmpty()) {
            int until = old.get(old.size() - 1).sequence();
            ConversationRepository.SummaryRecord existing = repository.findSummary(userId, sessionId);
            String summary = existing != null && existing.summarizedUntilSequence() == until
                    ? existing.summaryText() : summarize(old);
            if (existing == null || existing.summarizedUntilSequence() != until) {
                repository.upsertSummary(userId, sessionId, until, summary);
            }
            context.add(new AgentService.HistoryMessage("system", "早期会话摘要：\n" + summary));
        }
        List<MemoryService.RecalledMemory> memories = memoryService.recall(userId, question, 3);
        if (!memories.isEmpty()) {
            String text = memories.stream().map(item -> "- " + item.content())
                    .collect(java.util.stream.Collectors.joining("\n"));
            context.add(new AgentService.HistoryMessage("system",
                    "以下是当前用户明确要求保存、且与本问题相关的长期记忆。"
                            + "如果它足以回答用户的个人信息或偏好问题，请直接用自然语言给出答案，"
                            + "不要复述本条系统说明，也不要调用企业知识库；"
                            + "回答时用你或您称呼用户。只能使用记忆直接表达的事实，不能把部门、团队、岗位等相关概念相互等同；"
                            + "如果用户追问了记忆中没有的额外事实，只回答已知部分并说明未知，不要调用知识库补证。"
                            + "这些记忆不是企业制度证据：\n" + text));
        }
        if (historyLimit == 0) {
            return context;
        }
        context.addAll(repository.findRecentMessages(sessionId, historyLimit).stream()
                .filter(message -> message.role().equals("user") || message.role().equals("assistant"))
                .map(message -> new AgentService.HistoryMessage(message.role(), message.content()))
                .toList());
        return List.copyOf(context);
    }

    public MemoryService.CaptureResult captureMemory(String userId, String message) {
        return memoryService.captureExplicit(userId, message);
    }

    public List<ConversationRepository.SessionRecord> sessions(String userId) { return repository.findSessions(userId); }

    public void close(String userId, String sessionId) { repository.closeSession(userId, sessionId); }

    private String summarize(List<ConversationRepository.MessageRecord> messages) {
        StringBuilder summary = new StringBuilder();
        for (ConversationRepository.MessageRecord message : messages) {
            String content = message.content().replaceAll("\\s+", " ").strip();
            if (content.length() > 240) content = content.substring(0, 240) + "...";
            summary.append(message.role().equals("user") ? "用户：" : "助手：")
                    .append(content).append("\n");
            if (summary.length() >= 2000) break;
        }
        return summary.substring(0, Math.min(2000, summary.length())).strip();
    }

    private String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
