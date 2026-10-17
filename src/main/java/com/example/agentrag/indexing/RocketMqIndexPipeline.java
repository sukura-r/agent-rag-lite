package com.example.agentrag.indexing;

import com.example.agentrag.embedding.EmbeddingService;
import com.example.agentrag.knowledge.KnowledgeRepository;
import com.example.agentrag.vector.VectorStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class RocketMqIndexPipeline {
    private static final Logger log= LoggerFactory.getLogger(RocketMqIndexPipeline.class);
    private final IndexTaskRepository tasks; private final KnowledgeRepository knowledge;
    private final EmbeddingService embedding; private final VectorStore vectors; private final ObjectMapper json;
    private final boolean enabled; private final String nameserver,topic,producerGroup,consumerGroup; private final int maxAttempts,batchSize;
    private DefaultMQProducer producer; private DefaultMQPushConsumer consumer;

    public RocketMqIndexPipeline(IndexTaskRepository tasks,KnowledgeRepository knowledge,EmbeddingService embedding,
                                 VectorStore vectors,ObjectMapper json,
                                 @Value("${app.indexing.rocketmq-enabled:true}") boolean enabled,
                                 @Value("${app.indexing.nameserver}") String nameserver,@Value("${app.indexing.topic}") String topic,
                                 @Value("${app.indexing.producer-group}") String producerGroup,@Value("${app.indexing.consumer-group}") String consumerGroup,
                                 @Value("${app.indexing.max-attempts:3}") int maxAttempts,@Value("${app.indexing.batch-size:20}") int batchSize){
        this.tasks=tasks;this.knowledge=knowledge;this.embedding=embedding;this.vectors=vectors;this.json=json;this.enabled=enabled;
        this.nameserver=nameserver;this.topic=topic;this.producerGroup=producerGroup;this.consumerGroup=consumerGroup;
        this.maxAttempts=maxAttempts;this.batchSize=batchSize;
    }

    @PostConstruct public void start() throws Exception { if(!enabled)return;
        producer=new DefaultMQProducer(producerGroup);producer.setNamesrvAddr(nameserver);producer.setSendMsgTimeout(5000);producer.start();
        consumer=new DefaultMQPushConsumer(consumerGroup);consumer.setNamesrvAddr(nameserver);consumer.subscribe(topic,"*");
        consumer.setConsumeThreadMin(1);consumer.setConsumeThreadMax(2);consumer.setPullBatchSize(1);
        consumer.registerMessageListener((MessageListenerConcurrently)this::consume);consumer.start();
        log.info("RocketMQ索引链路启动，nameserver={}, topic={}",nameserver,topic);
    }

    @PreDestroy public void stop(){if(consumer!=null)consumer.shutdown();if(producer!=null)producer.shutdown();}

    @Scheduled(fixedDelayString="${app.indexing.relay-delay-ms:1000}")
    public void relay(){if(!enabled||producer==null)return;for(var event:tasks.pendingEvents(batchSize))try{
        Message message=new Message(topic,event.type(),event.id(),event.payload().getBytes(StandardCharsets.UTF_8));
        producer.send(message);tasks.markSent(event.id());
    }catch(Exception e){tasks.markSendFailed(event.id(),e.getMessage());log.warn("Outbox投递失败，eventId={}",event.id(),e);}}

    private ConsumeConcurrentlyStatus consume(List<MessageExt> messages, ConsumeConcurrentlyContext context){
        for(MessageExt message:messages)try{
            Map<?,?> payload=json.readValue(message.getBody(),Map.class);String documentId=String.valueOf(payload.get("documentId"));
            int version=((Number)payload.get("version")).intValue();
            if(!tasks.begin(documentId,version,maxAttempts))continue;
            String taskType=String.valueOf(payload.get("taskType"));
            if("DELETE".equals(taskType)){
                vectors.deleteDocument(documentId);tasks.deleted(documentId,version);continue;
            }
            List<KnowledgeRepository.ChunkRecord> chunks=knowledge.findDocumentChunks(documentId,version);
            if(chunks.isEmpty())throw new IllegalStateException("文档没有可索引分块："+documentId+" v"+version);
            List<VectorStore.VectorRecord> records=new ArrayList<>();
            for(var chunk:chunks)records.add(new VectorStore.VectorRecord(chunk.id(),documentId,embedding.embed(chunk.content())));
            // 新版本使用不同的确定性Chunk主键，可与旧版本同时存在。全部写完后才原子切换MySQL生效版本。
            List<String> vectorsBeforeWrite=vectors.documentVectorIds(documentId);
            vectors.upsert(records);
            var activation=tasks.ready(documentId,version,chunks.size());
            // 切换完成后再清理旧版本。清理失败不会回滚已经生效的新版本，后续对账会再次清理残留。
            var newIds=records.stream().map(VectorStore.VectorRecord::id).collect(java.util.stream.Collectors.toSet());
            var staleIds=vectorsBeforeWrite.stream().filter(id->!newIds.contains(id)).toList();
            try{vectors.deleteIds(staleIds);}
            catch(Exception cleanupError){log.warn("新版本已生效，但旧版本向量异步清理失败，等待对账处理，documentId={}, oldVersion={}",
                    documentId,activation.oldVersion(),cleanupError);}
        }catch(Exception e){try{Map<?,?> p=json.readValue(message.getBody(),Map.class);tasks.failed(String.valueOf(p.get("documentId")),((Number)p.get("version")).intValue(),e.getMessage(),maxAttempts);}catch(Exception ignored){}
            log.error("索引消息消费失败，msgId={}",message.getMsgId(),e);return ConsumeConcurrentlyStatus.RECONSUME_LATER;}
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }
}
