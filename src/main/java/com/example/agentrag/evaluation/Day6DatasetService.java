package com.example.agentrag.evaluation;

import com.example.agentrag.knowledge.KnowledgeRepository;
import com.example.agentrag.knowledge.KnowledgeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class Day6DatasetService {

    private final ObjectMapper objectMapper;
    private final KnowledgeService knowledgeService;
    private final KnowledgeRepository repository;

    public Day6DatasetService(ObjectMapper objectMapper, KnowledgeService knowledgeService,
                              KnowledgeRepository repository) {
        this.objectMapper = objectMapper;
        this.knowledgeService = knowledgeService;
        this.repository = repository;
    }

    public LoadResult loadCorpus() {
        CorpusDataset dataset = read("evaluation/day6-corpus.json", CorpusDataset.class);
        List<String> loaded = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (CorpusDocument document : dataset.documents()) {
            if (repository.findDocumentIdByDatasetKey(document.key()).isPresent()) {
                skipped.add(document.key());
                continue;
            }
            KnowledgeService.IngestResult result = knowledgeService.ingest(document.title(), document.content());
            repository.insertDatasetMapping(document.key(), result.documentId(), document.domain(),
                    document.sourceType(), document.version(), document.publicDisplay());
            loaded.add(document.key());
        }
        return new LoadResult(dataset.name(), dataset.documents().size(), loaded, skipped, statistics());
    }

    public DatasetReport statistics() {
        KnowledgeRepository.DatasetStatistics total = repository.datasetStatistics();
        return new DatasetReport(total, repository.domainStatistics(), evaluationDataset().queries().size());
    }

    public Set<String> datasetDocumentIds() {
        CorpusDataset dataset = read("evaluation/day6-corpus.json", CorpusDataset.class);
        Set<String> documentIds = new HashSet<>();
        for (CorpusDocument document : dataset.documents()) {
            repository.findDocumentIdByDatasetKey(document.key()).ifPresent(documentIds::add);
        }
        if (documentIds.size() != dataset.documents().size()) {
            throw new IllegalStateException("Day 6评测语料尚未完整导入，请先调用dataset/load");
        }
        return Set.copyOf(documentIds);
    }

    public EvaluationDataset evaluationDataset() {
        return read("evaluation/day6-queries.json", EvaluationDataset.class);
    }

    private <T> T read(String path, Class<T> type) {
        try {
            return objectMapper.readValue(new ClassPathResource(path).getInputStream(), type);
        } catch (IOException exception) {
            throw new IllegalStateException("读取Day6数据集失败：" + path, exception);
        }
    }

    public record CorpusDataset(String name, List<CorpusDocument> documents) {
    }

    public record CorpusDocument(String key, String domain, String title, String sourceType,
                                 int version, boolean publicDisplay, String content) {
    }

    public record EvaluationDataset(String name, List<EvaluationQuery> queries) {
    }

    public record EvaluationQuery(String id, String type, String question, List<String> relevantTexts,
                                  List<String> hardNegativeTexts, List<String> answerFacts) {
    }

    public record LoadResult(String dataset, int configuredDocuments, List<String> loaded,
                             List<String> skipped, DatasetReport statistics) {
    }

    public record DatasetReport(KnowledgeRepository.DatasetStatistics total,
                                List<KnowledgeRepository.DomainStatistics> domains,
                                int evaluationQueries) {
    }
}
