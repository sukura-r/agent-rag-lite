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
public class Day12DatasetService {
    private final ObjectMapper json;
    private final KnowledgeService knowledge;
    private final KnowledgeRepository repository;
    private final Day6DatasetService day6;

    public Day12DatasetService(ObjectMapper json,KnowledgeService knowledge,
                               KnowledgeRepository repository,Day6DatasetService day6){
        this.json=json;this.knowledge=knowledge;this.repository=repository;this.day6=day6;
    }

    public Day6DatasetService.LoadResult loadSupplement(){
        var dataset=read("evaluation/day12-corpus.json",Day6DatasetService.CorpusDataset.class);
        List<String> loaded=new ArrayList<>(),skipped=new ArrayList<>();
        for(var document:dataset.documents()){
            if(repository.findDocumentIdByDatasetKey(document.key()).isPresent()){skipped.add(document.key());continue;}
            var result=knowledge.ingest(document.title(),document.content());
            repository.insertDatasetMapping(document.key(),result.documentId(),document.domain(),
                    document.sourceType(),document.version(),document.publicDisplay());
            loaded.add(document.key());
        }
        return new Day6DatasetService.LoadResult(dataset.name(),dataset.documents().size(),loaded,skipped,statistics());
    }

    public Day6DatasetService.EvaluationDataset evaluationDataset(){
        var base=day6.evaluationDataset();
        var supplement=read("evaluation/day12-queries.json",Day6DatasetService.EvaluationDataset.class);
        List<Day6DatasetService.EvaluationQuery> all=new ArrayList<>(base.queries());all.addAll(supplement.queries());
        return new Day6DatasetService.EvaluationDataset("day12-expanded-retrieval-evaluation-v1",List.copyOf(all));
    }

    public Set<String> datasetDocumentIds(){
        Set<String> ids=new HashSet<>(day6.datasetDocumentIds());
        var supplement=read("evaluation/day12-corpus.json",Day6DatasetService.CorpusDataset.class);
        for(var document:supplement.documents())repository.findDocumentIdByDatasetKey(document.key()).ifPresent(ids::add);
        int expected=read("evaluation/day6-corpus.json",Day6DatasetService.CorpusDataset.class).documents().size()
                +supplement.documents().size();
        if(ids.size()!=expected)throw new IllegalStateException("Day12扩展语料尚未完整导入");
        return Set.copyOf(ids);
    }

    public Day6DatasetService.DatasetReport statistics(){
        var total=repository.datasetStatistics();
        return new Day6DatasetService.DatasetReport(total,repository.domainStatistics(),evaluationDataset().queries().size());
    }

    private <T>T read(String path,Class<T> type){try{
        return json.readValue(new ClassPathResource(path).getInputStream(),type);
    }catch(IOException e){throw new IllegalStateException("读取Day12数据集失败："+path,e);}}
}
