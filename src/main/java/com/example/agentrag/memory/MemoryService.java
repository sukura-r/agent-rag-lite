package com.example.agentrag.memory;

import com.example.agentrag.embedding.EmbeddingService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class MemoryService {
    private final MemoryRepository repository;
    private final EmbeddingService embeddingService;
    private final ObjectMapper objectMapper;
    private final int ttlDays;
    private final double threshold;

    public MemoryService(MemoryRepository repository, EmbeddingService embeddingService,
                         ObjectMapper objectMapper,
                         @Value("${app.memory.ttl-days:30}") int ttlDays,
                         @Value("${app.memory.recall-threshold:0.55}") double threshold) {
        this.repository = repository;
        this.embeddingService = embeddingService;
        this.objectMapper = objectMapper;
        this.ttlDays = Math.max(1, ttlDays);
        this.threshold = threshold;
    }

    public CaptureResult captureExplicit(String userId, String message) {
        String text = message == null ? "" : message.strip();
        String remember = extractAfter(text, "请记住：", "请记住", "记住：");
        if (remember != null && !remember.isBlank()) {
            String key = memoryKey(remember);
            float[] vector = embeddingService.embed(remember);
            repository.upsert(id(), userId, key, remember, json(vector), LocalDateTime.now().plusDays(ttlDays));
            return new CaptureResult("SAVED", key, remember);
        }
        String forget = extractAfter(text, "请忘记：", "忘记：", "删除记忆：", "忘记");
        if (forget != null && !forget.isBlank()) {
            String key = memoryKey(forget);
            int changed = repository.deactivateByKey(userId, key);
            return new CaptureResult(changed > 0 ? "DELETED" : "NOT_FOUND", key, forget);
        }
        return new CaptureResult("IGNORED", "", "");
    }

    public List<RecalledMemory> recall(String userId, String query, int topK) {
        List<MemoryRepository.MemoryRecord> memories = repository.active(userId);
        if (memories.isEmpty() || query == null || query.isBlank()) return List.of();
        float[] queryVector = embeddingService.embed(query);
        List<RecalledMemory> result = new ArrayList<>();
        for (MemoryRepository.MemoryRecord memory : memories) {
            double score = cosine(queryVector, vector(memory.embeddingJson()));
            if (score >= threshold) result.add(new RecalledMemory(memory.id(), memory.key(), memory.content(), score));
        }
        return result.stream().sorted(Comparator.comparingDouble(RecalledMemory::score).reversed())
                .limit(Math.max(1, topK)).toList();
    }

    public List<MemoryRepository.MemoryRecord> list(String userId) { return repository.active(userId); }

    public void delete(String userId, String memoryId) {
        if (repository.deactivateById(userId, memoryId) == 0) {
            throw new IllegalArgumentException("记忆不存在或无权删除：" + memoryId);
        }
    }

    private String memoryKey(String content) {
        String normalized = content.strip().replaceAll("[。！!？?]$", "");
        int separator = normalized.indexOf('是');
        String key = separator > 0 ? normalized.substring(0, separator) : normalized;
        return key.toLowerCase(Locale.ROOT).substring(0, Math.min(128, key.length()));
    }

    private String extractAfter(String value, String... prefixes) {
        for (String prefix : prefixes) if (value.startsWith(prefix)) return value.substring(prefix.length()).strip();
        return null;
    }

    private String json(float[] vector) {
        try { return objectMapper.writeValueAsString(vector); }
        catch (JsonProcessingException e) { throw new IllegalStateException("序列化Memory向量失败", e); }
    }

    private float[] vector(String json) {
        try {
            List<Float> values = objectMapper.readValue(json, new TypeReference<>() { });
            float[] result = new float[values.size()];
            for (int i = 0; i < values.size(); i++) result[i] = values.get(i);
            return result;
        } catch (JsonProcessingException e) { throw new IllegalStateException("解析Memory向量失败", e); }
    }

    private double cosine(float[] a, float[] b) {
        if (a.length != b.length || a.length == 0) return -1;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]; }
        return na == 0 || nb == 0 ? -1 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private String id() { return UUID.randomUUID().toString().replace("-", ""); }

    public record CaptureResult(String action, String key, String content) { }
    public record RecalledMemory(String id, String key, String content, double score) { }
}
