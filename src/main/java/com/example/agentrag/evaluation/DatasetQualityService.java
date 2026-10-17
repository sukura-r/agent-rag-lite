package com.example.agentrag.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class DatasetQualityService {
    private static final Pattern SENSITIVE=Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)|(?<!\\d)\\d{17}[0-9Xx](?!\\d)|[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private final ObjectMapper json;
    public DatasetQualityService(ObjectMapper json){this.json=json;}

    public Report inspect(){
        var baseCorpus=read("evaluation/day6-corpus.json",Day6DatasetService.CorpusDataset.class);
        var extraCorpus=read("evaluation/day12-corpus.json",Day6DatasetService.CorpusDataset.class);
        var baseQueries=read("evaluation/day6-queries.json",Day6DatasetService.EvaluationDataset.class);
        var extraQueries=read("evaluation/day12-queries.json",Day6DatasetService.EvaluationDataset.class);
        List<String> issues=new ArrayList<>();Set<String> keys=new HashSet<>(),questions=new HashSet<>(),ids=new HashSet<>();
        var documents=new ArrayList<Day6DatasetService.CorpusDocument>();documents.addAll(baseCorpus.documents());documents.addAll(extraCorpus.documents());
        for(var d:documents){
            if(d.key()==null||d.key().isBlank()||!keys.add(d.key()))issues.add("文档key为空或重复："+d.key());
            if(d.title()==null||d.title().isBlank()||d.content()==null||d.content().isBlank())issues.add("文档标题或内容为空："+d.key());
            if(SENSITIVE.matcher(d.content()==null?"":d.content()).find())issues.add("文档疑似包含敏感信息："+d.key());
        }
        var queries=new ArrayList<Day6DatasetService.EvaluationQuery>();queries.addAll(baseQueries.queries());queries.addAll(extraQueries.queries());
        for(var q:queries){
            if(q.id()==null||q.id().isBlank()||!ids.add(q.id()))issues.add("问题id为空或重复："+q.id());
            String normalized=q.question()==null?"":q.question().replaceAll("\\s+","");
            if(normalized.isBlank()||!questions.add(normalized))issues.add("问题为空或重复："+q.id());
            if(q.relevantTexts()==null)issues.add("问题缺少relevantTexts："+q.id());
        }
        return new Report(documents.size(),queries.size(),keys.size(),questions.size(),issues.size(),List.copyOf(issues));
    }

    private <T>T read(String path,Class<T> type){try{return json.readValue(new ClassPathResource(path).getInputStream(),type);}
    catch(IOException e){throw new IllegalStateException("数据质量检查读取失败："+path,e);}}
    public record Report(int documents,int queries,int uniqueDocumentKeys,int uniqueQuestions,int issueCount,List<String> issues){}
}
