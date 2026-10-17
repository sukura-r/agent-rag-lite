package com.example.agentrag.retrieval;

import com.example.agentrag.embedding.EmbeddingService;
import com.example.agentrag.knowledge.KnowledgeRepository;
import com.example.agentrag.vector.VectorStore;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class HybridRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(HybridRetrievalService.class);

    private final EmbeddingService embeddingService;
    private final VectorStore vectorStore;
    private final KnowledgeRepository repository;
    private final Bm25Retriever bm25Retriever;
    private final LlmReranker reranker;
    private final RetrievalProperties properties;

    public HybridRetrievalService(EmbeddingService embeddingService, VectorStore vectorStore,
                                  KnowledgeRepository repository, Bm25Retriever bm25Retriever,
                                  LlmReranker reranker, RetrievalProperties properties) {
        this.embeddingService = embeddingService;
        this.vectorStore = vectorStore;
        this.repository = repository;
        this.bm25Retriever = bm25Retriever;
        this.reranker = reranker;
        this.properties = properties;
    }

    public RetrievalStages retrieveStages(String query) {
        return retrieveStages(query, null);
    }

    public RetrievalStages retrieveStages(String query, Set<String> allowedDocumentIds) {
        long started = System.nanoTime();
        int candidateK = properties.candidateK();
        List<KnowledgeRepository.ChunkRecord> corpus = repository.findAllReadyChunks().stream()
                .filter(chunk -> allowedDocumentIds == null || allowedDocumentIds.contains(chunk.documentId()))
                .toList();
        long corpusReady = System.nanoTime();
        int vectorCandidateK = allowedDocumentIds == null ? candidateK : Math.max(candidateK, corpus.size() * 3);
        List<VectorStore.VectorHit> vectorHits = vectorStore.search(embeddingService.embed(query), vectorCandidateK);
        long vectorReady = System.nanoTime();
        Map<String, KnowledgeRepository.ChunkRecord> byId = new HashMap<>();
        repository.findChunks(vectorHits.stream().map(VectorStore.VectorHit::chunkId).toList())
                .forEach(chunk -> byId.put(chunk.id(), chunk));
        List<RetrievalCandidate> vector = vectorHits.stream()
                .filter(hit -> byId.containsKey(hit.chunkId()))
                .filter(hit -> allowedDocumentIds == null
                        || allowedDocumentIds.contains(byId.get(hit.chunkId()).documentId()))
                .map(hit -> new RetrievalCandidate(byId.get(hit.chunkId()), hit.score(), 0, 0, 0))
                .limit(candidateK)
                .toList();
        long hydrated = System.nanoTime();

        List<Bm25Retriever.ScoredChunk> keywordHits = bm25Retriever.search(query, corpus, candidateK);
        List<RetrievalCandidate> keyword = keywordHits.stream()
                .map(hit -> new RetrievalCandidate(hit.chunk(), 0, hit.score(), 0, 0)).toList();

        List<RetrievalCandidate> fused = fuse(vector, keyword, properties.rrfK());
        List<RetrievalCandidate> rerankInput = fused.stream().limit(properties.rerankCandidates()).toList();
        long fusionReady = System.nanoTime();
        boolean rerankApplied = properties.rerankEnabled() && shouldRerank(vector, keyword, rerankInput);
        List<RetrievalCandidate> reranked = rerankApplied
                ? reranker.rerank(query, rerankInput) : rerankInput;
        long finished = System.nanoTime();
        log.info("检索阶段耗时：corpus={}ms, embedding+milvus={}ms, hydrate={}ms, bm25+fusion={}ms, "
                        + "rerank={}ms, rerankApplied={}, candidates={}, total={}ms",
                millis(started, corpusReady), millis(corpusReady, vectorReady), millis(vectorReady, hydrated),
                millis(hydrated, fusionReady), millis(fusionReady, finished), rerankApplied,
                rerankInput.size(), millis(started, finished));
        return new RetrievalStages(vector, keyword, fused, reranked);
    }

    boolean shouldRerank(List<RetrievalCandidate> vector, List<RetrievalCandidate> keyword,
                         List<RetrievalCandidate> rerankInput) {
        if (rerankInput.size() <= 1) {
            return false;
        }
        if (!vector.isEmpty() && !keyword.isEmpty()
                && vector.get(0).chunk().id().equals(keyword.get(0).chunk().id())) {
            return false;
        }
        return true;
    }

    private long millis(long start, long end) {
        return (end - start) / 1_000_000;
    }

    public List<RetrievalCandidate> search(String query, int topK) {
        RetrievalStages stages = retrieveStages(query);
        List<RetrievalCandidate> selected = properties.rerankEnabled() ? stages.reranked() : stages.fused();
        return selected.stream().filter(this::hasEnoughEvidence).limit(Math.max(1, topK)).toList();
    }

    public boolean hasEnoughEvidence(RetrievalCandidate candidate){
        return candidate.vectorScore()>=properties.minimumVectorScore()
                || candidate.keywordScore()>=properties.minimumKeywordScore();
    }

    List<RetrievalCandidate> fuse(List<RetrievalCandidate> vector, List<RetrievalCandidate> keyword, int rrfK) {
        Map<String, MutableCandidate> combined = new LinkedHashMap<>();
        for (int i = 0; i < vector.size(); i++) {
            RetrievalCandidate item = vector.get(i);
            MutableCandidate candidate = combined.computeIfAbsent(item.chunk().id(),
                    ignored -> new MutableCandidate(item.chunk()));
            candidate.vectorScore = item.vectorScore();
            candidate.fusionScore += 1.0 / (rrfK + i + 1.0);
        }
        for (int i = 0; i < keyword.size(); i++) {
            RetrievalCandidate item = keyword.get(i);
            MutableCandidate candidate = combined.computeIfAbsent(item.chunk().id(),
                    ignored -> new MutableCandidate(item.chunk()));
            candidate.keywordScore = item.keywordScore();
            candidate.fusionScore += 1.0 / (rrfK + i + 1.0);
        }
        List<RetrievalCandidate> result = new ArrayList<>();
        combined.values().forEach(item -> result.add(new RetrievalCandidate(item.chunk, item.vectorScore,
                item.keywordScore, item.fusionScore, 0)));
        return result.stream().sorted(Comparator.comparingDouble(RetrievalCandidate::fusionScore).reversed())
                .toList();
    }

    public record RetrievalStages(List<RetrievalCandidate> vector,
                                  List<RetrievalCandidate> keyword,
                                  List<RetrievalCandidate> fused,
                                  List<RetrievalCandidate> reranked) {
    }

    private static final class MutableCandidate {
        private final KnowledgeRepository.ChunkRecord chunk;
        private double vectorScore;
        private double keywordScore;
        private double fusionScore;

        private MutableCandidate(KnowledgeRepository.ChunkRecord chunk) {
            this.chunk = chunk;
        }
    }
}
