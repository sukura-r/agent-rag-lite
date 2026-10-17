package com.example.agentrag.knowledge;

import com.example.agentrag.embedding.EmbeddingService;
import com.example.agentrag.vector.VectorStore;
import com.example.agentrag.retrieval.HybridRetrievalService;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class KnowledgeService {

    private final TextChunker textChunker;
    private final KnowledgeRepository repository;
    private final VectorStore vectorStore;
    private final HybridRetrievalService retrievalService;
    private final ObjectMapper objectMapper;

    public KnowledgeService(TextChunker textChunker, EmbeddingService embeddingService,
                            KnowledgeRepository repository, VectorStore vectorStore,
                            HybridRetrievalService retrievalService, ObjectMapper objectMapper) {
        this.textChunker = textChunker;
        this.repository = repository;
        this.vectorStore = vectorStore;
        this.retrievalService = retrievalService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public IngestResult ingest(String title, String content) {
        String documentId = id();
        List<String> chunks = textChunker.split(content);
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("文档内容不能为空");
        }
        repository.insertDocument(documentId, title, content);
        repository.insertDocumentVersion(documentId,1,title,content);
        for (int i = 0; i < chunks.size(); i++) repository.insertChunk(deterministicChunkId(documentId,1,i),documentId,1,i,chunks.get(i));
        String taskId=id(),eventId=id();
        try { repository.insertIndexTaskAndOutbox(taskId,eventId,documentId,1,
                objectMapper.writeValueAsString(Map.of("eventId",eventId,"documentId",documentId,"version",1,"taskType","UPSERT")));
        } catch (Exception e) { throw new IllegalStateException("创建索引任务失败",e); }
        return new IngestResult(documentId, chunks.size(), taskId, "PENDING");
    }

    @Transactional
    public IngestResult update(String documentId,String title,String content) {
        int version=repository.lockAndGetVersion(documentId)+1;
        List<String> chunks=textChunker.split(content);
        if(chunks.isEmpty())throw new IllegalArgumentException("文档内容不能为空");
        repository.stageDocumentVersion(documentId,title,content,version);
        for(int i=0;i<chunks.size();i++)repository.insertChunk(
                deterministicChunkId(documentId,version,i),documentId,version,i,chunks.get(i));
        return createTask(documentId,version,"UPSERT",chunks.size());
    }

    @Transactional
    public IngestResult delete(String documentId) {
        int version=repository.lockAndGetVersion(documentId)+1;
        repository.markDeleting(documentId,version);
        return createTask(documentId,version,"DELETE",0);
    }

    private IngestResult createTask(String documentId,int version,String taskType,int chunkCount){
        String taskId=id(),eventId=id();
        try{repository.insertIndexTaskAndOutbox(taskId,eventId,documentId,version,taskType,
                objectMapper.writeValueAsString(Map.of("eventId",eventId,"documentId",documentId,
                        "version",version,"taskType",taskType)));
        }catch(Exception e){throw new IllegalStateException("创建索引任务失败",e);}
        return new IngestResult(documentId,chunkCount,taskId,"PENDING");
    }

    public List<SearchResult> search(String query, int topK) {
        return retrievalService.search(query, topK).stream().map(item -> {
            KnowledgeRepository.ChunkRecord chunk = item.chunk();
            double finalScore = item.rerankScore() > 0 ? item.rerankScore() : item.fusionScore();
            return new SearchResult(chunk.id(), chunk.documentId(), chunk.title(), chunk.chunkIndex(),
                    chunk.content(), (float) finalScore);
        }).toList();
    }

    public List<SearchResult> getReadyDocumentChunks(String documentId) {
        return repository.findReadyDocumentChunks(documentId).stream()
                .map(chunk -> new SearchResult(chunk.id(), chunk.documentId(), chunk.title(),
                        chunk.chunkIndex(), chunk.content(), 1.0f))
                .toList();
    }

    private String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String deterministicChunkId(String documentId,int version,int index){try{
        byte[] bytes=MessageDigest.getInstance("SHA-256").digest((documentId+":"+version+":"+index).getBytes(StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(bytes).substring(0,32);
    }catch(Exception e){throw new IllegalStateException(e);}}

    public record IngestResult(String documentId, int chunkCount, String taskId, String status) {
    }

    public record SearchResult(String chunkId, String documentId, String title, int chunkIndex,
                               String content, float score) {
    }
}
