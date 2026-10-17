package com.example.agentrag.indexing;

import com.example.agentrag.knowledge.KnowledgeRepository;
import com.example.agentrag.vector.VectorStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Map;
import java.util.UUID;

@Service
public class IndexReconciliationService {
    private final KnowledgeRepository knowledge;
    private final IndexTaskRepository tasks;
    private final VectorStore vectors;
    private final ObjectMapper json;

    public IndexReconciliationService(KnowledgeRepository knowledge,IndexTaskRepository tasks,
                                      VectorStore vectors,ObjectMapper json){
        this.knowledge=knowledge;this.tasks=tasks;this.vectors=vectors;this.json=json;
    }

    public Result reconcile(){
        int checked=0,requeued=0,residualCleaned=0;
        for(var document:knowledge.findDocumentsForReconciliation()){
            checked++;
            var actual=new HashSet<>(vectors.documentVectorIds(document.id()));
            if("DELETED".equals(document.status())){
                if(!actual.isEmpty()){vectors.deleteDocument(document.id());residualCleaned++;}
                continue;
            }
            var expected=new HashSet<>(knowledge.findDocumentChunks(document.id(),document.version()).stream()
                    .map(KnowledgeRepository.ChunkRecord::id).toList());
            if(!expected.equals(actual) && requeue(document.id(),document.version(),"UPSERT"))requeued++;
        }
        return new Result(checked,requeued,residualCleaned);
    }

    private boolean requeue(String documentId,int version,String taskType){
        try{
            String eventId=id();
            String payload=json.writeValueAsString(Map.of("eventId",eventId,"documentId",documentId,
                    "version",version,"taskType",taskType));
            return tasks.requeue(documentId,version,taskType,eventId,payload);
        }catch(Exception e){throw new IllegalStateException("创建对账补偿任务失败",e);}
    }

    private String id(){return UUID.randomUUID().toString().replace("-","");}
    public record Result(int checkedDocuments,int requeuedDocuments,int cleanedDeletedDocuments){}
}
