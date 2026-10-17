package com.example.agentrag.evaluation;

import com.example.agentrag.knowledge.KnowledgeRepository;
import com.example.agentrag.knowledge.KnowledgeService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class CapacityDatasetService {
    private static final List<String> DOMAINS=List.of("人事","财务","采购","IT运维","信息安全","行政","项目管理","客户服务");
    private final KnowledgeService knowledge;
    private final KnowledgeRepository repository;

    public CapacityDatasetService(KnowledgeService knowledge,KnowledgeRepository repository){
        this.knowledge=knowledge;this.repository=repository;
    }

    public GenerateResult generate(int requested,String batchName){
        int count=Math.max(1,Math.min(1000,requested));
        String batchId=(batchName==null||batchName.isBlank()?"capacity":safe(batchName))+"-"+id().substring(0,8);
        List<String> taskIds=new ArrayList<>();int generated=0,skipped=0,totalChars=0;
        for(int i=0;i<count;i++){
            String key=batchId+"-"+String.format("%04d",i);
            if(repository.capacityKeyExists(key)){skipped++;continue;}
            String domain=DOMAINS.get(i%DOMAINS.size());String content=content(domain,i);
            var result=knowledge.ingest("容量压测-"+domain+"-"+String.format("%04d",i),content);
            repository.insertCapacityMapping(key,result.documentId(),batchId,domain,content.length());
            taskIds.add(result.taskId());generated++;totalChars+=content.length();
        }
        return new GenerateResult(batchId,count,generated,skipped,totalChars,taskIds,repository.capacityStatistics());
    }

    private String content(String domain,int index){
        int approvalDays=1+(index%15);int amount=1000+(index%20)*500;
        String paragraph="编号CAP-"+domain+"-"+String.format("%05d",index)+"。本记录属于容量压测生成数据，不代表真实公司制度。"
                +domain+"事项应至少提前"+approvalDays+"个工作日提交，金额超过"+amount+"元时由部门负责人复核。"
                +"申请材料包括业务说明、费用明细、负责人意见和风险确认。处理完成后保留审批记录，用于审计与容量测试。";
        return paragraph+paragraph+"唯一校验码为CAPACITY-"+index+"，不得把本数据用于检索效果评测。";
    }

    private String safe(String value){return value.replaceAll("[^A-Za-z0-9_-]","").substring(0,Math.min(20,value.replaceAll("[^A-Za-z0-9_-]","").length()));}
    private String id(){return UUID.randomUUID().toString().replace("-","");}

    public record GenerateResult(String batchId,int requested,int generated,int skipped,int generatedCharacters,
                                 List<String> taskIds,KnowledgeRepository.CapacityStatistics total){}
}
