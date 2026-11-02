package com.example.agentrag.memory;

import com.example.agentrag.embedding.EmbeddingService;
import com.example.agentrag.retrieval.ChineseTokenizer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CancellationException;

@Service
public class MemoryService {
    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);
    private static final Set<String> PREFERENCES = Set.of("偏好:编程语言", "偏好:回答风格");
    private static final Set<String> STOP_WORDS = Set.of("我的", "什么", "怎么", "请问", "一下", "这个", "那个", "可以", "告诉", "多少", "哪个");
    private final MemoryRepository repository;
    private final EmbeddingService embeddingService;
    private final ObjectMapper objectMapper;
    private final int ttlDays;
    private final double threshold;
    private final boolean autoCaptureEnabled;
    private final int candidateLimit;
    private final LlmMemoryExtractor modelExtractor;
    private final MemoryCandidateExtractor extractor = new MemoryCandidateExtractor();
    private final ChineseTokenizer tokenizer = new ChineseTokenizer();

    @Autowired
    public MemoryService(MemoryRepository repository, EmbeddingService embeddingService, ObjectMapper objectMapper,
                         LlmMemoryExtractor modelExtractor,
                         @Value("${app.memory.ttl-days:30}") int ttlDays,
                         @Value("${app.memory.recall-threshold:0.55}") double threshold,
                         @Value("${app.memory.auto-capture-enabled:true}") boolean autoCaptureEnabled,
                         @Value("${app.memory.max-recall-candidates:200}") int candidateLimit) {
        this.repository = repository; this.embeddingService = embeddingService; this.objectMapper = objectMapper;
        this.modelExtractor = modelExtractor; this.ttlDays = Math.max(1, ttlDays); this.threshold = threshold;
        this.autoCaptureEnabled = autoCaptureEnabled; this.candidateLimit = Math.max(1, Math.min(1000, candidateLimit));
    }

    public MemoryService(MemoryRepository repository, EmbeddingService embeddingService, ObjectMapper objectMapper,
                         int ttlDays, double threshold, boolean autoCaptureEnabled) {
        this(repository, embeddingService, objectMapper, null, ttlDays, threshold, autoCaptureEnabled, 200);
    }

    public CaptureResult capture(String userId, String message) { return capture(userId, message, null); }

    public CaptureResult capture(String userId, String message, Source source) {
        checkInterrupted();
        CaptureResult explicit = captureExplicit(userId, message, source);
        if (!explicit.action().equals("IGNORED") || !autoCaptureEnabled) return explicit;
        CaptureResult saved = explicit;
        try {
            var rule = extractor.extract(message);
            if (rule.isPresent()) {
                var fact = rule.get();
                return save(userId, fact.key(), fact.content(), source, "RULE", message.strip());
            }
            if (modelExtractor != null) {
                for (var fact : modelExtractor.extract(message)) {
                    checkInterrupted();
                    CaptureResult result = save(userId, fact.key(), fact.content(), source, "MODEL", fact.evidence());
                    if (saved.action().equals("IGNORED")) saved = result;
                }
            }
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (RuntimeException failure) {
            log.warn("自动记忆写入降级，不阻断问答：{}", failure.getClass().getSimpleName());
        }
        return saved;
    }

    public CaptureResult captureExplicit(String userId, String message) { return captureExplicit(userId, message, null); }

    private CaptureResult captureExplicit(String userId, String message, Source source) {
        String text = message == null ? "" : message.strip();
        String remember = extractAfter(text, "请记住：", "请记住", "记住：");
        if (remember != null && !remember.isBlank()) {
            if (remember.length() > 1000) throw new IllegalArgumentException("单条长期记忆不能超过1000字符");
            var rule = extractor.extract(remember);
            if (rule.isPresent()) return save(userId, rule.get().key(), rule.get().content(), source, "EXPLICIT", remember);
            if (modelExtractor != null) {
                var facts = modelExtractor.extract(remember);
                if (facts.size() == 1) return save(userId, facts.get(0).key(), facts.get(0).content(), source, "EXPLICIT", remember);
            }
            return save(userId, memoryKey(remember), remember, source, "EXPLICIT", remember);
        }
        String forget = extractAfter(text, "请忘记：", "忘记：", "删除记忆：", "忘记");
        if (forget != null && !forget.isBlank()) {
            String key = extractor.extract(forget).map(MemoryCandidateExtractor.Candidate::key)
                    .orElseGet(() -> memoryKey(forget));
            int changed = repository.deactivateByKey(userId, key);
            return new CaptureResult(changed > 0 ? "DELETED" : "NOT_FOUND", key, forget);
        }
        return new CaptureResult("IGNORED", "", "");
    }

    public List<RecalledMemory> recall(String userId, String query, int topK) { return recall(userId, query, topK, false); }
    public List<RecalledMemory> recallForContext(String userId, String query, int topK) { return recall(userId, query, topK, true); }

    private List<RecalledMemory> recall(String userId, String query, int topK, boolean includePreferences) {
        if (topK <= 0 || query == null || query.isBlank()) return List.of();
        checkInterrupted();
        List<MemoryRepository.MemoryRecord> memories;
        try {
            memories = repository.candidates(userId, candidateLimit).stream()
                    .filter(m -> userId.equals(m.userId()) && "ACTIVE".equals(m.status()))
                    .filter(m -> m.expiresAt() == null || m.expiresAt().isAfter(LocalDateTime.now())).toList();
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (RuntimeException failure) {
            log.warn("Memory读取降级：{}", failure.getClass().getSimpleName());
            return List.of();
        }
        if (memories.isEmpty()) return List.of();
        float[] queryVector = new float[0];
        try { queryVector = embeddingService.embed(query); }
        catch (CancellationException cancelled) { throw cancelled; }
        catch (RuntimeException failure) { log.warn("Memory向量召回降级：{}", failure.getClass().getSimpleName()); }
        Set<String> terms = terms(query);
        List<ScoredMemory> candidates = new ArrayList<>();
        for (var memory : memories) {
            double semantic = -1;
            try { semantic = cosine(queryVector, vector(memory.embeddingJson())); }
            catch (RuntimeException invalid) { log.debug("跳过无效Memory向量：id={}", memory.id()); }
            Set<String> documentTerms = terms(memory.key() + " " + memory.content());
            long hits = terms.stream().filter(documentTerms::contains).count();
            candidates.add(new ScoredMemory(memory, semantic, hits));
        }
        Map<String, Double> ranks = new HashMap<>();
        List<ScoredMemory> semantic = candidates.stream().filter(c -> c.semantic() >= threshold)
                .sorted(Comparator.comparingDouble(ScoredMemory::semantic).reversed()).toList();
        List<ScoredMemory> lexical = candidates.stream().filter(c -> c.keywordHits() > 0)
                .sorted(Comparator.comparingLong(ScoredMemory::keywordHits).reversed()).toList();
        for (int i = 0; i < semantic.size(); i++) ranks.merge(semantic.get(i).memory().id(), 1.0 / (61 + i), Double::sum);
        for (int i = 0; i < lexical.size(); i++) ranks.merge(lexical.get(i).memory().id(), 1.0 / (61 + i), Double::sum);
        List<RecalledMemory> selected = new ArrayList<>();
        Set<String> chosen = new HashSet<>();
        int usedCharacters = 0;
        if (includePreferences) {
            for (var memory : memories) {
                if (PREFERENCES.contains(memory.key()) && selected.size() < Math.min(2, topK)
                        && usedCharacters + memory.content().length() <= 1000) {
                    selected.add(new RecalledMemory(memory.id(), memory.key(), memory.content(), 1.0));
                    chosen.add(memory.id()); usedCharacters += memory.content().length();
                }
            }
        }
        List<ScoredMemory> ordered = candidates.stream().filter(c -> ranks.containsKey(c.memory().id()))
                .sorted(Comparator.comparingDouble((ScoredMemory c) -> ranks.get(c.memory().id())).reversed()).toList();
        for (var candidate : ordered) {
            var memory = candidate.memory();
            if (selected.size() >= topK) break;
            if (chosen.contains(memory.id()) || usedCharacters + memory.content().length() > 1000) continue;
            selected.add(new RecalledMemory(memory.id(), memory.key(), memory.content(), ranks.get(memory.id())));
            chosen.add(memory.id()); usedCharacters += memory.content().length();
        }
        return List.copyOf(selected);
    }

    private Set<String> terms(String text) {
        Set<String> result = new HashSet<>();
        for (String term : tokenizer.tokenize(text)) if (term.length() > 1 && !STOP_WORDS.contains(term)) result.add(term);
        return result;
    }

    private CaptureResult save(String userId, String key, String content, Source source, String origin, String evidence) {
        if (content.length() > 1000) throw new IllegalArgumentException("单条长期记忆不能超过1000字符");
        float[] vector = embeddingService.embed(content);
        checkInterrupted();
        String embedding = json(vector);
        if (source == null) repository.upsert(id(), userId, key, content, embedding, LocalDateTime.now().plusDays(ttlDays));
        else repository.upsertWithEvidence(id(), userId, key, content, embedding, LocalDateTime.now().plusDays(ttlDays),
                source.taskId(), source.sessionId(), evidence, origin);
        return new CaptureResult("SAVED", key, content);
    }

    public List<MemoryRepository.MemoryRecord> list(String userId) { return repository.active(userId); }
    public MemoryRepository.MemoryEvidence evidence(String userId, String memoryId) {
        return repository.evidence(userId, memoryId).orElseThrow(() -> new IllegalArgumentException("记忆来源不存在或无权访问"));
    }
    public void delete(String userId, String memoryId) {
        if (repository.deactivateById(userId, memoryId) == 0) throw new IllegalArgumentException("记忆不存在或无权删除：" + memoryId);
    }

    private String memoryKey(String content) {
        String normalized = content.strip().replaceAll("[。！!？?]$", "");
        if (Set.of("我的主要编程语言", "我的编程语言", "主要编程语言").contains(normalized)) return "偏好:编程语言";
        if (Set.of("我的回答风格", "回答风格").contains(normalized)) return "偏好:回答风格";
        int separator = normalized.indexOf('是');
        String key = separator > 0 ? normalized.substring(0, separator) : normalized;
        return key.toLowerCase(Locale.ROOT).substring(0, Math.min(128, key.length()));
    }
    private String extractAfter(String value, String... prefixes) {
        for (String prefix : prefixes) if (value.startsWith(prefix)) return value.substring(prefix.length()).strip();
        return null;
    }
    private String json(float[] vector) {
        if (vector == null || vector.length == 0) throw new IllegalStateException("Memory向量为空");
        for (float value : vector) if (!Float.isFinite(value)) throw new IllegalStateException("Memory向量非有限数值");
        try { return objectMapper.writeValueAsString(vector); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("序列化Memory向量失败", failure); }
    }
    private float[] vector(String value) {
        try { return objectMapper.readValue(value, float[].class); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Memory向量无效", failure); }
    }
    private double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) return -1;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            if (!Float.isFinite(a[i]) || !Float.isFinite(b[i])) return -1;
            dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i];
        }
        return na == 0 || nb == 0 ? -1 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
    private void checkInterrupted() { if (Thread.currentThread().isInterrupted()) throw new CancellationException("Memory处理已取消"); }
    private String id() { return UUID.randomUUID().toString().replace("-", ""); }
    public record Source(String sessionId, String taskId) { }
    public record CaptureResult(String action, String key, String content) { }
    // score is rank-fusion score, not a probability or raw cosine.
    public record RecalledMemory(String id, String key, String content, double score) { }
    private record ScoredMemory(MemoryRepository.MemoryRecord memory, double semantic, long keywordHits) { }
}
