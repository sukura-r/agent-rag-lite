package com.example.agentrag.task;

import com.example.agentrag.knowledge.KnowledgeRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ProjectReportService {
    private final JdbcTemplate jdbc;
    private final KnowledgeRepository knowledge;
    public ProjectReportService(JdbcTemplate jdbc,KnowledgeRepository knowledge){this.jdbc=jdbc;this.knowledge=knowledge;}

    public Report report(){
        Overall overall=jdbc.queryForObject("SELECT COUNT(*) documents,COALESCE(SUM(CHAR_LENGTH(content)),0) characters,"
                        +"COALESCE(SUM(chunk_count),0) chunks,SUM(status='READY') ready_documents FROM knowledge_document",
                (rs,n)->new Overall(rs.getInt("documents"),rs.getLong("characters"),rs.getLong("chunks"),rs.getInt("ready_documents")));
        Map<String,Integer> documentStates=new LinkedHashMap<>();
        jdbc.query("SELECT status,COUNT(*) amount FROM knowledge_document GROUP BY status ORDER BY status",
                rs->{documentStates.put(rs.getString("status"),rs.getInt("amount"));});
        Map<String,Integer> taskStates=new LinkedHashMap<>();
        jdbc.query("SELECT status,COUNT(*) amount FROM document_index_task GROUP BY status ORDER BY status",
                rs->{taskStates.put(rs.getString("status"),rs.getInt("amount"));});
        LatestEvaluation latest=jdbc.query("SELECT dataset_name,query_count,created_at FROM retrieval_evaluation_run ORDER BY created_at DESC LIMIT 1",
                (rs,n)->new LatestEvaluation(rs.getString("dataset_name"),rs.getInt("query_count"),
                        rs.getObject("created_at",LocalDateTime.class))).stream().findFirst().orElse(null);
        long events=jdbc.queryForObject("SELECT COUNT(*) FROM agent_task_event",Long.class);
        return new Report(overall,knowledge.datasetStatistics(),knowledge.capacityStatistics(),documentStates,taskStates,
                latest,events,Runtime.getRuntime().availableProcessors(),Runtime.getRuntime().maxMemory()/1024/1024);
    }

    public record Overall(int documents,long characters,long chunks,int readyDocuments){}
    public record LatestEvaluation(String dataset,int queries,LocalDateTime createdAt){}
    public record Report(Overall allKnowledge,KnowledgeRepository.DatasetStatistics evaluationCorpus,
                         KnowledgeRepository.CapacityStatistics capacityCorpus,Map<String,Integer> documentStates,
                         Map<String,Integer> indexTaskStates,LatestEvaluation latestEvaluation,long persistedAgentEvents,
                         int availableProcessors,long javaMaxMemoryMb){}
}
