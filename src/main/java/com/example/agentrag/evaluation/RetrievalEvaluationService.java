package com.example.agentrag.evaluation;

import com.example.agentrag.knowledge.KnowledgeRepository;
import com.example.agentrag.retrieval.HybridRetrievalService;
import com.example.agentrag.retrieval.RetrievalCandidate;
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
import java.util.UUID;

@Service
public class RetrievalEvaluationService {

    private final Day6DatasetService datasetService;
    private final Day12DatasetService day12DatasetService;
    private final HybridRetrievalService retrievalService;
    private final KnowledgeRepository repository;
    private final ObjectMapper objectMapper;

    public RetrievalEvaluationService(Day6DatasetService datasetService,Day12DatasetService day12DatasetService,
                                      HybridRetrievalService retrievalService,
                                      KnowledgeRepository repository,
                                      ObjectMapper objectMapper) {
        this.datasetService = datasetService;
        this.day12DatasetService=day12DatasetService;
        this.retrievalService = retrievalService;
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public EvaluationReport run(int topK) {
        return runDataset(topK,datasetService.evaluationDataset(),datasetService.datasetDocumentIds(),datasetService.statistics());
    }

    public EvaluationReport runDay12(int topK){
        return runDataset(topK,day12DatasetService.evaluationDataset(),day12DatasetService.datasetDocumentIds(),day12DatasetService.statistics());
    }

    private EvaluationReport runDataset(int topK,Day6DatasetService.EvaluationDataset dataset,
                                        java.util.Set<String> allowedDocumentIds,
                                        Day6DatasetService.DatasetReport datasetReport) {
        int evaluatedTopK = Math.max(1, topK);
        Map<String, MetricAccumulator> accumulators = new LinkedHashMap<>();
        accumulators.put("VECTOR", new MetricAccumulator(evaluatedTopK));
        accumulators.put("BM25", new MetricAccumulator(evaluatedTopK));
        accumulators.put("RRF", new MetricAccumulator(evaluatedTopK));
        accumulators.put("RERANK", new MetricAccumulator(evaluatedTopK));
        List<QueryResult> details = new ArrayList<>();
        Instant started = Instant.now();
        for (Day6DatasetService.EvaluationQuery query : dataset.queries()) {
            HybridRetrievalService.RetrievalStages stages = retrievalService.retrieveStages(query.question(), allowedDocumentIds);
            Map<String, List<RetrievalCandidate>> rankings = Map.of(
                    "VECTOR", stages.vector(),
                    "BM25", stages.keyword(),
                    "RRF", stages.fused(),
                    "RERANK", stages.reranked());
            Map<String, QueryStageResult> stageResults = new LinkedHashMap<>();
            for (Map.Entry<String, MetricAccumulator> entry : accumulators.entrySet()) {
                List<RetrievalCandidate> ranking = rankings.get(entry.getKey()).stream()
                        .limit(evaluatedTopK).toList();
                QueryStageResult result = evaluateQuery(query, ranking);
                entry.getValue().add(query, result);
                stageResults.put(entry.getKey(), result);
            }
            details.add(new QueryResult(query.id(), query.type(), query.question(), query.relevantTexts(), stageResults));
        }
        Map<String, Metrics> metrics = new LinkedHashMap<>();
        accumulators.forEach((name, accumulator) -> metrics.put(name, accumulator.finish()));
        Map<String,Map<String,Metrics>> metricsByType=new LinkedHashMap<>();
        for(String type:dataset.queries().stream().map(Day6DatasetService.EvaluationQuery::type).distinct().toList()){
            Map<String,Metrics> perStage=new LinkedHashMap<>();
            for(String stage:accumulators.keySet()){
                MetricAccumulator acc=new MetricAccumulator(evaluatedTopK);
                for(QueryResult detail:details)if(type.equals(detail.type())){
                    var original=dataset.queries().stream().filter(q->q.id().equals(detail.id())).findFirst().orElseThrow();
                    acc.add(original,detail.stages().get(stage));
                }
                perStage.put(stage,acc.finish());
            }
            metricsByType.put(type,perStage);
        }
        EvaluationReport report = new EvaluationReport(id(), dataset.name(), dataset.queries().size(), evaluatedTopK,
                Duration.between(started, Instant.now()).toMillis(), datasetReport, metrics,metricsByType, details);
        repository.saveEvaluationRun(report.runId(), report.dataset(), report.queryCount(), json(report));
        return report;
    }

    private QueryStageResult evaluateQuery(Day6DatasetService.EvaluationQuery query,
                                           List<RetrievalCandidate> ranking) {
        if (query.relevantTexts() == null || query.relevantTexts().isEmpty()) {
            boolean refused=ranking.isEmpty()||!retrievalService.hasEnoughEvidence(ranking.get(0));
            return new QueryStageResult(refused ? 1.0 : 0.0, 0, 0,false,
                    ranking.stream().map(this::hit).toList());
        }
        int foundUnits = 0;
        for (String expected : query.relevantTexts()) {
            String normalizedExpected = normalize(expected);
            if (ranking.stream().anyMatch(candidate -> normalize(candidate.chunk().content()).contains(normalizedExpected))) {
                foundUnits++;
            }
        }
        int relevantResults = 0;
        int firstRelevantRank = 0;
        for (int i = 0; i < ranking.size(); i++) {
            RetrievalCandidate candidate = ranking.get(i);
            boolean relevant = isRelevant(candidate, query.relevantTexts());
            if (relevant) {
                relevantResults++;
                if (firstRelevantRank == 0) {
                    firstRelevantRank = i + 1;
                }
            }
        }
        double recall = (double) foundUnits / query.relevantTexts().size();
        double precision = ranking.isEmpty() ? 0 : (double) relevantResults / ranking.size();
        double reciprocalRank = firstRelevantRank == 0 ? 0 : 1.0 / firstRelevantRank;
        int firstRelevant=firstRelevantRank;
        int firstHardNegativeRank=0;
        if(query.hardNegativeTexts()!=null&&!query.hardNegativeTexts().isEmpty()){
            for(int i=0;i<ranking.size();i++)if(isRelevant(ranking.get(i),query.hardNegativeTexts())){firstHardNegativeRank=i+1;break;}
        }
        boolean hardNegativeOutranked=firstHardNegativeRank>0&&(firstRelevant==0||firstHardNegativeRank<firstRelevant);
        return new QueryStageResult(recall, precision, reciprocalRank,hardNegativeOutranked,
                ranking.stream().map(this::hit).toList());
    }

    private boolean isRelevant(RetrievalCandidate candidate, List<String> expectedTexts) {
        String content = normalize(candidate.chunk().content());
        return expectedTexts.stream().map(this::normalize).anyMatch(content::contains);
    }

    private RankingHit hit(RetrievalCandidate candidate) {
        return new RankingHit(candidate.chunk().id(), candidate.chunk().documentId(), candidate.chunk().title(),
                candidate.chunk().chunkIndex(), candidate.vectorScore(), candidate.keywordScore(),
                candidate.fusionScore(), candidate.rerankScore());
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private String json(EvaluationReport report) {
        try {
            return objectMapper.writeValueAsString(report);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("序列化评测结果失败", exception);
        }
    }

    private String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public record EvaluationReport(String runId, String dataset, int queryCount, int topK,
                                   long durationMillis, Day6DatasetService.DatasetReport datasetReport,
                                   Map<String, Metrics> metrics,Map<String,Map<String,Metrics>> metricsByType,
                                   List<QueryResult> queries) {
    }

    public record Metrics(double recallAtK, double precisionAtK, double mrr,
                          double hitRateAtK, double noAnswerAccuracy,
                          double hardNegativeOutrankRate,int hardNegativeQueries,
                          int answerableQueries, int noAnswerQueries) {
    }

    public record QueryResult(String id, String type, String question, List<String> relevantTexts,
                              Map<String, QueryStageResult> stages) {
    }

    public record QueryStageResult(double recall, double precision, double reciprocalRank,
                                   boolean hardNegativeOutranked,
                                   List<RankingHit> hits) {
    }

    public record RankingHit(String chunkId, String documentId, String title, int chunkIndex,
                             double vectorScore, double keywordScore,
                             double fusionScore, double rerankScore) {
    }

    private static final class MetricAccumulator {
        private final int topK;
        private double recall;
        private double precision;
        private double reciprocalRank;
        private int hit;
        private int answerable;
        private int noAnswer;
        private int noAnswerCorrect;
        private int hardNegativeQueries;
        private int hardNegativeOutranked;

        private MetricAccumulator(int topK) {
            this.topK = topK;
        }

        private void add(Day6DatasetService.EvaluationQuery query, QueryStageResult result) {
            if (query.relevantTexts() == null || query.relevantTexts().isEmpty()) {
                noAnswer++;
                if (result.recall() == 1.0) {
                    noAnswerCorrect++;
                }
                return;
            }
            answerable++;
            recall += result.recall();
            precision += result.precision();
            reciprocalRank += result.reciprocalRank();
            if (result.reciprocalRank() > 0) {
                hit++;
            }
            if(query.hardNegativeTexts()!=null&&!query.hardNegativeTexts().isEmpty()){
                hardNegativeQueries++;
                if(result.hardNegativeOutranked())hardNegativeOutranked++;
            }
        }

        private Metrics finish() {
            return new Metrics(answerable == 0 ? 0 : recall / answerable,
                    answerable == 0 ? 0 : precision / answerable,
                    answerable == 0 ? 0 : reciprocalRank / answerable,
                    answerable == 0 ? 0 : (double) hit / answerable,
                    noAnswer == 0 ? 0 : (double) noAnswerCorrect / noAnswer,
                    hardNegativeQueries==0?0:(double)hardNegativeOutranked/hardNegativeQueries,
                    hardNegativeQueries,
                    answerable, noAnswer);
        }
    }
}
