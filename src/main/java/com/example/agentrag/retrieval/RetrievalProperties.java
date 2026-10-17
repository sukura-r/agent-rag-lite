package com.example.agentrag.retrieval;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.retrieval")
public record RetrievalProperties(int candidateK, int rrfK, int rerankCandidates, boolean rerankEnabled,
                                  double minimumVectorScore,double minimumKeywordScore) {

    public RetrievalProperties {
        candidateK = Math.max(1, candidateK);
        rrfK = Math.max(1, rrfK);
        rerankCandidates = Math.max(1, rerankCandidates);
        minimumVectorScore=minimumVectorScore<=0?0.5:minimumVectorScore;
        minimumKeywordScore=minimumKeywordScore<=0?2.0:minimumKeywordScore;
    }
}
