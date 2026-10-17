package com.example.agentrag.evaluation;

import com.example.agentrag.knowledge.KnowledgeService;
import com.example.agentrag.model.ChatModel;
import com.example.agentrag.rag.RagPromptBuilder;
import com.example.agentrag.retrieval.HybridRetrievalService;
import com.example.agentrag.retrieval.RetrievalCandidate;
import com.example.agentrag.knowledge.KnowledgeRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class RagAblationEvaluationService {

    private static final Pattern CITATION = Pattern.compile("\\[(\\d+)]");
    private static final String REFUSAL = "知识库证据不足，无法回答。";
    private final Day12DatasetService datasetService;
    private final HybridRetrievalService retrievalService;
    private final ChatModel chatModel;
    private final RagPromptBuilder promptBuilder;
    private final KnowledgeRepository repository;
    private final ObjectMapper objectMapper;

    public RagAblationEvaluationService(Day12DatasetService datasetService,
                                        HybridRetrievalService retrievalService, ChatModel chatModel,
                                        RagPromptBuilder promptBuilder, KnowledgeRepository repository,
                                        ObjectMapper objectMapper) {
        this.datasetService = datasetService;
        this.retrievalService = retrievalService;
        this.chatModel = chatModel;
        this.promptBuilder = promptBuilder;
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public Report run(int requestedQuestions, int topK) {
        Day6DatasetService.EvaluationDataset dataset = datasetService.evaluationDataset();
        int questionCount = Math.max(1, Math.min(requestedQuestions, dataset.queries().size()));
        int evaluatedTopK = Math.max(1, Math.min(topK, 10));
        List<Day6DatasetService.EvaluationQuery> queries = stratifiedSelection(dataset.queries(), questionCount);
        Set<String> allowedDocumentIds = datasetService.datasetDocumentIds();
        Map<String, Accumulator> accumulators = new LinkedHashMap<>();
        accumulators.put("PURE_LLM", new Accumulator());
        accumulators.put("VECTOR_RAG", new Accumulator());
        accumulators.put("HYBRID_RAG", new Accumulator());
        accumulators.put("FULL_RAG", new Accumulator());
        List<QueryDetail> details = new ArrayList<>();
        Instant runStarted = Instant.now();

        for (Day6DatasetService.EvaluationQuery query : queries) {
            HybridRetrievalService.RetrievalStages stages = retrievalService.retrieveStages(query.question(), allowedDocumentIds);
            Map<String, List<RetrievalCandidate>> candidates = new LinkedHashMap<>();
            candidates.put("VECTOR_RAG", stages.vector().stream().limit(evaluatedTopK).toList());
            candidates.put("HYBRID_RAG", stages.fused().stream().limit(evaluatedTopK).toList());
            candidates.put("FULL_RAG", stages.reranked().stream()
                    .filter(retrievalService::hasEnoughEvidence).limit(evaluatedTopK).toList());

            Map<String, VariantResult> variants = new LinkedHashMap<>();
            VariantResult pure = executePure(query);
            variants.put("PURE_LLM", pure);
            accumulators.get("PURE_LLM").add(query, pure);
            for (String variant : List.of("VECTOR_RAG", "HYBRID_RAG", "FULL_RAG")) {
                VariantResult result = executeRag(query, candidates.get(variant));
                variants.put(variant, result);
                accumulators.get(variant).add(query, result);
            }
            details.add(new QueryDetail(query.id(), query.type(), query.question(), answerFacts(query), variants));
        }

        Map<String, Metrics> metrics = new LinkedHashMap<>();
        accumulators.forEach((name, accumulator) -> metrics.put(name, accumulator.finish()));
        Report report = new Report(id(), dataset.name(), queries.size(), dataset.queries().size(), evaluatedTopK,
                chatModel.mode(), "deterministic-required-fact-coverage-v1",
                Duration.between(runStarted, Instant.now()).toMillis(), metrics, details);
        repository.saveEvaluationRun(report.runId(), "rag-ablation-" + dataset.name(), report.queryCount(), json(report));
        return report;
    }

    public Report rescore(Report source) {
        Map<String, Accumulator> accumulators = new LinkedHashMap<>();
        source.metrics().keySet().forEach(name -> accumulators.put(name, new Accumulator()));
        List<QueryDetail> rescoredQueries = new ArrayList<>();
        for (QueryDetail query : source.queries()) {
            Map<String, VariantResult> variants = new LinkedHashMap<>();
            for (Map.Entry<String, VariantResult> entry : query.variants().entrySet()) {
                VariantResult previous = entry.getValue();
                VariantResult rescored = scoreFacts(query.requiredFacts(), previous.answer(), previous.evidence(),
                        previous.latencyMillis(), previous.error());
                variants.put(entry.getKey(), rescored);
                accumulators.get(entry.getKey()).add(!query.requiredFacts().isEmpty(), rescored);
            }
            rescoredQueries.add(new QueryDetail(query.id(), query.type(), query.question(),
                    query.requiredFacts(), variants));
        }
        Map<String, Metrics> metrics = new LinkedHashMap<>();
        accumulators.forEach((name, accumulator) -> metrics.put(name, accumulator.finish()));
        Report report = new Report(id(), source.dataset(), source.queryCount(), source.availableQueryCount(),
                source.topK(), source.modelMode(), "deterministic-required-fact-coverage-v2",
                source.durationMillis(), metrics, rescoredQueries);
        repository.saveEvaluationRun(report.runId(), "rag-ablation-rescore-" + report.dataset(),
                report.queryCount(), json(report));
        return report;
    }

    private VariantResult executePure(Day6DatasetService.EvaluationQuery query) {
        Instant started = Instant.now();
        try {
            String answer = chatModel.answer("你是问答助手。请直接、简洁回答；不确定时明确说无法回答。不要假装看到了知识库。",
                    "问题：" + query.question() + "\n只输出答案，不要解释答题过程。 /no_think");
            return score(query, answer, List.of(), Duration.between(started, Instant.now()).toMillis());
        } catch (RuntimeException exception) {
            return failed(exception, started);
        }
    }

    private VariantResult executeRag(Day6DatasetService.EvaluationQuery query,
                                     List<RetrievalCandidate> candidates) {
        Instant started = Instant.now();
        if (candidates.isEmpty()) {
            return score(query, REFUSAL, List.of(), Duration.between(started, Instant.now()).toMillis());
        }
        List<KnowledgeService.SearchResult> evidence = candidates.stream().map(item ->
                new KnowledgeService.SearchResult(item.chunk().id(), item.chunk().documentId(),
                        item.chunk().title(), item.chunk().chunkIndex(), item.chunk().content(),
                        (float) finalScore(item))).toList();
        try {
            String answer = chatModel.answer(promptBuilder.systemPrompt(),
                    promptBuilder.userPrompt(query.question(), evidence) +
                            "\n评测要求：证据中存在答案时尽量保留原文中的关键时间、数字和条件，不要省略。 /no_think");
            return score(query, answer, evidence, Duration.between(started, Instant.now()).toMillis());
        } catch (RuntimeException exception) {
            return failed(exception, started);
        }
    }

    private VariantResult failed(RuntimeException exception, Instant started) {
        return new VariantResult("", List.of(), 0, false, false, false, false,
                Duration.between(started, Instant.now()).toMillis(), exception.getMessage());
    }

    private VariantResult score(Day6DatasetService.EvaluationQuery query, String answer,
                                List<KnowledgeService.SearchResult> evidence, long latencyMillis) {
        List<String> required = answerFacts(query);
        List<Evidence> values = evidence.stream().map(item -> new Evidence(item.chunkId(), item.documentId(),
                item.title(), item.content(), item.score())).toList();
        return scoreFacts(required, answer, values, latencyMillis, null);
    }

    private VariantResult scoreFacts(List<String> required, String answer, List<Evidence> evidence,
                                     long latencyMillis, String error) {
        boolean answerable = !required.isEmpty();
        int covered = 0;
        for (String fact : required) if (factCovered(answer, fact)) covered++;
        double factCoverage = answerable ? (double) covered / required.size() : 0;
        boolean refused = containsRefusal(answer);
        List<Integer> citations = citationNumbers(answer);
        boolean citationsValid = citations.stream().allMatch(number -> number >= 1 && number <= evidence.size());
        boolean citationPresent = !citations.isEmpty();
        boolean correct = error == null && (answerable ? factCoverage == 1.0 : refused);
        return new VariantResult(answer, evidence, factCoverage, correct, refused,
                citationPresent, citationsValid, latencyMillis, error);
    }

    private List<Day6DatasetService.EvaluationQuery> stratifiedSelection(
            List<Day6DatasetService.EvaluationQuery> all, int limit) {
        if (limit >= all.size()) return List.copyOf(all);
        Map<String, List<Day6DatasetService.EvaluationQuery>> byType = new LinkedHashMap<>();
        all.forEach(query -> byType.computeIfAbsent(query.type(), ignored -> new ArrayList<>()).add(query));
        List<Day6DatasetService.EvaluationQuery> selected = new ArrayList<>();
        int round = 0;
        while (selected.size() < limit) {
            boolean added = false;
            for (List<Day6DatasetService.EvaluationQuery> group : byType.values()) {
                if (round < group.size() && selected.size() < limit) {
                    selected.add(group.get(round));
                    added = true;
                }
            }
            if (!added) break;
            round++;
        }
        return List.copyOf(selected);
    }

    private List<String> answerFacts(Day6DatasetService.EvaluationQuery query) {
        if (query.answerFacts() != null) return query.answerFacts();
        return query.relevantTexts() == null ? List.of() : query.relevantTexts();
    }

    private boolean containsRefusal(String answer) {
        String value = answer == null ? "" : answer;
        return value.contains("无法回答") || value.contains("不能回答") || value.contains("不知道")
                || value.contains("没有检索到") || value.contains("证据不足") || value.contains("无法确定")
                || value.contains("未提供") || value.contains("没有相关信息");
    }

    private List<Integer> citationNumbers(String answer) {
        List<Integer> numbers = new ArrayList<>();
        Matcher matcher = CITATION.matcher(answer == null ? "" : answer);
        while (matcher.find()) numbers.add(Integer.parseInt(matcher.group(1)));
        return numbers;
    }

    private double finalScore(RetrievalCandidate item) {
        if (item.rerankScore() > 0) return item.rerankScore();
        if (item.fusionScore() > 0) return item.fusionScore();
        return item.vectorScore();
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("[\\s，。；：、,.!?！？:;\\[\\]]+", "")
                .toLowerCase(Locale.ROOT);
    }

    private boolean factCovered(String answer, String fact) {
        String normalizedAnswer = canonical(answer);
        String normalizedFact = canonical(fact);
        if (normalizedFact.isEmpty()) return false;
        if (normalizedAnswer.contains(normalizedFact)) return true;
        return (double) longestCommonSubsequence(normalizedAnswer, normalizedFact) / normalizedFact.length() >= 0.75;
    }

    private String canonical(String value) {
        String result = normalize(value)
                .replace("10个", "十个").replace("9个", "九个").replace("8个", "八个")
                .replace("7个", "七个").replace("6个", "六个").replace("5个", "五个")
                .replace("4个", "四个").replace("3个", "三个").replace("2个", "两个")
                .replace("1个", "一个");
        return result.replaceAll("至少|最少|最晚|最迟|必须|应当|应该|需要|需|为|进行|启动", "");
    }

    private int longestCommonSubsequence(String left, String right) {
        int[] previous = new int[right.length() + 1];
        for (int i = 1; i <= left.length(); i++) {
            int[] current = new int[right.length() + 1];
            for (int j = 1; j <= right.length(); j++) {
                current[j] = left.charAt(i - 1) == right.charAt(j - 1)
                        ? previous[j - 1] + 1 : Math.max(previous[j], current[j - 1]);
            }
            previous = current;
        }
        return previous[right.length()];
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("保存RAG对比结果失败", exception); }
    }

    private String id() { return UUID.randomUUID().toString().replace("-", ""); }

    private final class Accumulator {
        private int answerable;
        private int noAnswer;
        private double factCoverage;
        private int correct;
        private int noAnswerCorrect;
        private int answerableRefused;
        private int citationExpected;
        private int citationPresent;
        private int citationValid;
        private int failures;
        private long latency;
        private final List<Long> latencies = new ArrayList<>();

        private void add(Day6DatasetService.EvaluationQuery query, VariantResult result) {
            add(!answerFacts(query).isEmpty(), result);
        }

        private void add(boolean hasAnswer, VariantResult result) {
            if (result.error() != null) failures++;
            if (hasAnswer) {
                answerable++;
                factCoverage += result.factCoverage();
                if (result.correct()) correct++;
                if (result.refused()) answerableRefused++;
            } else {
                noAnswer++;
                if (result.correct()) noAnswerCorrect++;
            }
            if (!result.evidence().isEmpty()) {
                citationExpected++;
                if (result.citationPresent()) citationPresent++;
                if (result.citationPresent() && result.citationsValid()) citationValid++;
            }
            latency += result.latencyMillis();
            latencies.add(result.latencyMillis());
        }

        private Metrics finish() {
            List<Long> sorted = latencies.stream().sorted().toList();
            int total = answerable + noAnswer;
            int p95Index = Math.max(0, (int) Math.ceil(sorted.size() * 0.95) - 1);
            return new Metrics(answerable, noAnswer,
                    answerable == 0 ? 0 : factCoverage / answerable,
                    answerable == 0 ? 0 : (double) correct / answerable,
                    noAnswer == 0 ? 0 : (double) noAnswerCorrect / noAnswer,
                    answerable == 0 ? 0 : (double) answerableRefused / answerable,
                    citationExpected == 0 ? 0 : (double) citationPresent / citationExpected,
                    citationExpected == 0 ? 0 : (double) citationValid / citationExpected,
                    total == 0 ? 0 : (double) latency / total,
                    sorted.isEmpty() ? 0 : sorted.get(p95Index), failures);
        }
    }

    public record Report(String runId, String dataset, int queryCount, int availableQueryCount, int topK,
                         String modelMode, String scoringMethod, long durationMillis,
                         Map<String, Metrics> metrics, List<QueryDetail> queries) { }
    public record Metrics(int answerableQueries, int noAnswerQueries, double requiredFactCoverage,
                          double answerableAccuracy, double noAnswerAccuracy, double falseRefusalRate,
                          double citationPresenceRate, double citationValidityRate,
                          double averageLatencyMillis, long p95LatencyMillis, int failures) { }
    public record QueryDetail(String id, String type, String question, List<String> requiredFacts,
                              Map<String, VariantResult> variants) { }
    public record VariantResult(String answer, List<Evidence> evidence, double factCoverage, boolean correct,
                                boolean refused, boolean citationPresent, boolean citationsValid,
                                long latencyMillis, String error) { }
    public record Evidence(String chunkId, String documentId, String title, String content, float score) { }
}
