package com.example.agentrag.evaluation;

import com.example.agentrag.embedding.EmbeddingService;
import com.example.agentrag.knowledge.KnowledgeRepository;
import com.example.agentrag.retrieval.Bm25Retriever;
import com.example.agentrag.retrieval.LlmReranker;
import com.example.agentrag.retrieval.RetrievalCandidate;
import com.example.agentrag.retrieval.RetrievalProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

@Service
public class PublicRetrievalBenchmarkService {

    private static final Logger log = LoggerFactory.getLogger(PublicRetrievalBenchmarkService.class);

    private final PublicBenchmarkDatasetService datasetService;
    private final EmbeddingService embeddingService;
    private final Bm25Retriever bm25Retriever;
    private final LlmReranker reranker;
    private final RetrievalProperties properties;
    private final KnowledgeRepository repository;
    private final ObjectMapper objectMapper;
    private volatile EmbeddingCache embeddingCache;

    public PublicRetrievalBenchmarkService(PublicBenchmarkDatasetService datasetService,
                                           EmbeddingService embeddingService, Bm25Retriever bm25Retriever,
                                           LlmReranker reranker, RetrievalProperties properties,
                                           KnowledgeRepository repository, ObjectMapper objectMapper) {
        this.datasetService = datasetService;
        this.embeddingService = embeddingService;
        this.bm25Retriever = bm25Retriever;
        this.reranker = reranker;
        this.properties = properties;
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public Report run(int topK, boolean enableRerank) {
        return run(topK, enableRerank, 0, Integer.MAX_VALUE, 1.0, 1.0);
    }

    public Report run(int topK, boolean enableRerank, int queryOffset, int queryLimit,
                      double vectorWeight, double keywordWeight) {
        int evaluatedTopK = Math.max(1, Math.min(topK, 20));
        if (queryOffset < 0 || queryLimit < 1) {
            throw new IllegalArgumentException("queryOffset必须大于等于0，queryLimit必须大于0");
        }
        if (vectorWeight < 0 || keywordWeight < 0 || vectorWeight + keywordWeight == 0) {
            throw new IllegalArgumentException("融合权重不能为负数，并且不能同时为0");
        }
        PublicBenchmarkDatasetService.Dataset dataset = datasetService.load();
        if (queryOffset >= dataset.queries().size()) {
            throw new IllegalArgumentException("queryOffset超出公开评测集范围");
        }
        int queryEnd = Math.min(dataset.queries().size(), queryOffset + queryLimit);
        List<PublicBenchmarkDatasetService.Query> selectedQueries =
                dataset.queries().subList(queryOffset, queryEnd);
        List<KnowledgeRepository.ChunkRecord> corpus = dataset.documents().stream()
                .map(document -> new KnowledgeRepository.ChunkRecord(document.id(), document.id(), 0,
                        document.text(), "DuRetrieval公开文本"))
                .toList();
        EmbeddingCache cached = embeddings(dataset);

        Map<String, Accumulator> accumulators = new LinkedHashMap<>();
        accumulators.put("VECTOR_RAG", new Accumulator(evaluatedTopK, 20260904L));
        accumulators.put("BM25", new Accumulator(evaluatedTopK, 20260905L));
        accumulators.put("HYBRID_RAG", new Accumulator(evaluatedTopK, 20260906L));
        if (enableRerank) accumulators.put("FULL_RAG", new Accumulator(evaluatedTopK, 20260907L));
        List<QueryDetail> details = new ArrayList<>();
        Instant started = Instant.now();
        long fallbackCountBefore = reranker.fallbackCount();

        for (int queryIndex = 0; queryIndex < selectedQueries.size(); queryIndex++) {
            PublicBenchmarkDatasetService.Query query = selectedQueries.get(queryIndex);
            float[] queryVector = cached.queryVectors().get(query.id());
            List<RetrievalCandidate> vector = vectorRanking(queryVector, corpus, cached.documentVectors(),
                    properties.candidateK());
            List<RetrievalCandidate> keyword = bm25Retriever.search(query.text(), corpus, properties.candidateK())
                    .stream().map(hit -> new RetrievalCandidate(hit.chunk(), 0, hit.score(), 0, 0)).toList();
            List<RetrievalCandidate> fused = fuse(vector, keyword, properties.rrfK(), vectorWeight, keywordWeight);
            List<RetrievalCandidate> full = enableRerank
                    ? reranker.rerank(query.text(), fused.stream().limit(properties.rerankCandidates()).toList())
                    : List.of();
            Map<String, List<RetrievalCandidate>> rankings = new LinkedHashMap<>();
            rankings.put("VECTOR_RAG", vector);
            rankings.put("BM25", keyword);
            rankings.put("HYBRID_RAG", fused);
            if (enableRerank) rankings.put("FULL_RAG", full);

            Map<String, QueryMetrics> queryMetrics = new LinkedHashMap<>();
            for (Map.Entry<String, Accumulator> entry : accumulators.entrySet()) {
                QueryMetrics metrics = evaluate(rankings.get(entry.getKey()),
                        dataset.relevantDocumentIds().get(query.id()), evaluatedTopK);
                entry.getValue().add(metrics);
                queryMetrics.put(entry.getKey(), metrics);
            }
            details.add(new QueryDetail(query.id(), query.text(),
                    dataset.relevantDocumentIds().get(query.id()), queryMetrics));
            if ((queryIndex + 1) % 25 == 0 || queryIndex + 1 == selectedQueries.size()) {
                log.info("公开检索评测进度：{}/{}，rerank={}，vectorWeight={}，keywordWeight={}",
                        queryIndex + 1, selectedQueries.size(), enableRerank, vectorWeight, keywordWeight);
            }
        }

        Map<String, Metrics> metrics = new LinkedHashMap<>();
        accumulators.forEach((name, value) -> metrics.put(name, value.finish()));
        Report report = new Report(id(), dataset.name(), dataset.datasetRevision(), dataset.qrelsRevision(),
                dataset.paperUrl(), dataset.selection(), dataset.licenseNote(), dataset.queries().size(),
                selectedQueries.size(), dataset.documents().size(), queryOffset, evaluatedTopK, enableRerank,
                vectorWeight, keywordWeight, reranker.fallbackCount() - fallbackCountBefore,
                Duration.between(started, Instant.now()).toMillis(), metrics, details);
        repository.saveEvaluationRun(report.runId(), "public-" + report.dataset(), report.queryCount(), json(report));
        return report;
    }

    private EmbeddingCache embeddings(PublicBenchmarkDatasetService.Dataset dataset) {
        String key = dataset.datasetRevision() + ":" + dataset.queries().size() + ":" + dataset.documents().size();
        EmbeddingCache current = embeddingCache;
        if (current != null && current.key().equals(key)) {
            return current;
        }
        synchronized (this) {
            current = embeddingCache;
            if (current != null && current.key().equals(key)) {
                return current;
            }
            log.info("开始计算公开评测Embedding：{}个查询，{}篇候选文档",
                    dataset.queries().size(), dataset.documents().size());
            List<float[]> documentValues = embeddingService.embedAll(dataset.documents().stream()
                    .map(PublicBenchmarkDatasetService.Document::text).toList());
            Map<String, float[]> documentVectors = new LinkedHashMap<>();
            for (int i = 0; i < dataset.documents().size(); i++) {
                documentVectors.put(dataset.documents().get(i).id(), documentValues.get(i));
            }
            List<float[]> queryValues = embeddingService.embedAll(dataset.queries().stream()
                    .map(PublicBenchmarkDatasetService.Query::text).toList());
            Map<String, float[]> queryVectors = new LinkedHashMap<>();
            for (int i = 0; i < dataset.queries().size(); i++) {
                queryVectors.put(dataset.queries().get(i).id(), queryValues.get(i));
            }
            current = new EmbeddingCache(key, Map.copyOf(documentVectors), Map.copyOf(queryVectors));
            embeddingCache = current;
            log.info("公开评测Embedding计算完成，后续参数实验复用内存缓存");
            return current;
        }
    }

    private List<RetrievalCandidate> vectorRanking(float[] query, List<KnowledgeRepository.ChunkRecord> corpus,
                                                   Map<String, float[]> vectors, int limit) {
        return corpus.stream().map(chunk -> new RetrievalCandidate(chunk,
                        cosine(query, vectors.get(chunk.id())), 0, 0, 0))
                .sorted(Comparator.comparingDouble(RetrievalCandidate::vectorScore).reversed())
                .limit(limit).toList();
    }

    private List<RetrievalCandidate> fuse(List<RetrievalCandidate> vector, List<RetrievalCandidate> keyword,
                                          int rrfK, double vectorWeight, double keywordWeight) {
        Map<String, MutableScore> combined = new HashMap<>();
        for (int i = 0; i < vector.size(); i++) {
            RetrievalCandidate item = vector.get(i);
            MutableScore score = combined.computeIfAbsent(item.chunk().id(), ignored -> new MutableScore(item.chunk()));
            score.vector = item.vectorScore();
            score.fusion += vectorWeight / (rrfK + i + 1.0);
        }
        for (int i = 0; i < keyword.size(); i++) {
            RetrievalCandidate item = keyword.get(i);
            MutableScore score = combined.computeIfAbsent(item.chunk().id(), ignored -> new MutableScore(item.chunk()));
            score.keyword = item.keywordScore();
            score.fusion += keywordWeight / (rrfK + i + 1.0);
        }
        return combined.values().stream()
                .map(item -> new RetrievalCandidate(item.chunk, item.vector, item.keyword, item.fusion, 0))
                .sorted(Comparator.comparingDouble(RetrievalCandidate::fusionScore).reversed()).toList();
    }

    private QueryMetrics evaluate(List<RetrievalCandidate> ranking, Set<String> relevant, int topK) {
        List<String> ids = ranking.stream().limit(topK).map(item -> item.chunk().id()).toList();
        long found = ids.stream().filter(relevant::contains).count();
        int firstRank = 0;
        double dcg = 0;
        int ndcgK = Math.min(topK, ranking.size());
        for (int i = 0; i < ndcgK; i++) {
            if (relevant.contains(ranking.get(i).chunk().id())) {
                if (firstRank == 0) firstRank = i + 1;
                dcg += 1.0 / (Math.log(i + 2) / Math.log(2));
            }
        }
        double idealDcg = 0;
        for (int i = 0; i < Math.min(topK, relevant.size()); i++) {
            idealDcg += 1.0 / (Math.log(i + 2) / Math.log(2));
        }
        return new QueryMetrics((double) found / relevant.size(), firstRank == 0 ? 0 : 1.0 / firstRank,
                firstRank > 0, idealDcg == 0 ? 0 : dcg / idealDcg, ids);
    }

    private double cosine(float[] left, float[] right) {
        double dot = 0, leftNorm = 0, rightNorm = 0;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        return leftNorm == 0 || rightNorm == 0 ? 0 : dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("保存公开评测结果失败", exception); }
    }

    private String id() { return UUID.randomUUID().toString().replace("-", ""); }

    private static final class MutableScore {
        private final KnowledgeRepository.ChunkRecord chunk;
        private double vector;
        private double keyword;
        private double fusion;
        private MutableScore(KnowledgeRepository.ChunkRecord chunk) { this.chunk = chunk; }
    }

    private static final class Accumulator {
        private static final int BOOTSTRAP_SAMPLES = 1000;
        private final int topK;
        private final long bootstrapSeed;
        private final List<QueryMetrics> values = new ArrayList<>();
        private Accumulator(int topK, long bootstrapSeed) {
            this.topK = topK;
            this.bootstrapSeed = bootstrapSeed;
        }
        private void add(QueryMetrics value) {
            values.add(value);
        }
        private Metrics finish() {
            double recall = mean(values.stream().mapToDouble(QueryMetrics::recallAtK).toArray());
            double mrr = mean(values.stream().mapToDouble(QueryMetrics::reciprocalRank).toArray());
            double hitRate = mean(values.stream().mapToDouble(value -> value.hit() ? 1 : 0).toArray());
            double ndcg = mean(values.stream().mapToDouble(QueryMetrics::ndcgAtK).toArray());
            return new Metrics(topK, recall, mrr, hitRate, ndcg,
                    bootstrap(values.stream().mapToDouble(QueryMetrics::recallAtK).toArray(), bootstrapSeed),
                    bootstrap(values.stream().mapToDouble(QueryMetrics::reciprocalRank).toArray(), bootstrapSeed + 1),
                    bootstrap(values.stream().mapToDouble(value -> value.hit() ? 1 : 0).toArray(), bootstrapSeed + 2),
                    bootstrap(values.stream().mapToDouble(QueryMetrics::ndcgAtK).toArray(), bootstrapSeed + 3));
        }
        private static double mean(double[] values) {
            double total = 0;
            for (double value : values) total += value;
            return values.length == 0 ? 0 : total / values.length;
        }
        private static ConfidenceInterval bootstrap(double[] values, long seed) {
            if (values.length == 0) return new ConfidenceInterval(0, 0);
            Random random = new Random(seed);
            double[] means = new double[BOOTSTRAP_SAMPLES];
            for (int sample = 0; sample < BOOTSTRAP_SAMPLES; sample++) {
                double total = 0;
                for (int i = 0; i < values.length; i++) {
                    total += values[random.nextInt(values.length)];
                }
                means[sample] = total / values.length;
            }
            java.util.Arrays.sort(means);
            int lower = (int) Math.floor(0.025 * (means.length - 1));
            int upper = (int) Math.ceil(0.975 * (means.length - 1));
            return new ConfidenceInterval(means[lower], means[upper]);
        }
    }

    private record EmbeddingCache(String key, Map<String, float[]> documentVectors,
                                  Map<String, float[]> queryVectors) { }

    public record Report(String runId, String dataset, String datasetRevision, String qrelsRevision,
                         String paperUrl, String selection, String licenseNote, int datasetQueryCount,
                         int queryCount, int corpusCount, int queryOffset, int topK, boolean rerankEnabled,
                         double vectorWeight, double keywordWeight, long rerankFallbackCount,
                         long durationMillis, Map<String, Metrics> metrics,
                         List<QueryDetail> queries) { }
    public record Metrics(int topK, double recallAtK, double mrr, double hitRateAtK, double ndcgAtK,
                          ConfidenceInterval recallAtKConfidence95, ConfidenceInterval mrrConfidence95,
                          ConfidenceInterval hitRateAtKConfidence95, ConfidenceInterval ndcgAtKConfidence95) { }
    public record ConfidenceInterval(double lower, double upper) { }
    public record QueryDetail(String id, String question, Set<String> relevantDocumentIds,
                              Map<String, QueryMetrics> stages) { }
    public record QueryMetrics(double recallAtK, double reciprocalRank, boolean hit, double ndcgAtK,
                               List<String> topDocumentIds) { }
}
