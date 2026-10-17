package com.example.agentrag.retrieval;

import com.example.agentrag.knowledge.KnowledgeRepository;

public record RetrievalCandidate(KnowledgeRepository.ChunkRecord chunk,
                                 double vectorScore,
                                 double keywordScore,
                                 double fusionScore,
                                 double rerankScore) {

    public RetrievalCandidate withRerankScore(double score) {
        return new RetrievalCandidate(chunk, vectorScore, keywordScore, fusionScore, score);
    }
}
