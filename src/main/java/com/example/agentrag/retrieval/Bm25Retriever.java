package com.example.agentrag.retrieval;

import com.example.agentrag.knowledge.KnowledgeRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class Bm25Retriever {

    private static final double K1 = 1.2;
    private static final double B = 0.75;
    private final ChineseTokenizer tokenizer;

    public Bm25Retriever(ChineseTokenizer tokenizer) {
        this.tokenizer = tokenizer;
    }

    public List<ScoredChunk> search(String query, List<KnowledgeRepository.ChunkRecord> corpus, int topK) {
        if (corpus.isEmpty()) {
            return List.of();
        }
        Set<String> queryTerms = new HashSet<>(tokenizer.tokenize(query));
        if (queryTerms.isEmpty()) {
            return List.of();
        }
        List<DocumentTerms> documents = new ArrayList<>();
        Map<String, Integer> documentFrequency = new HashMap<>();
        double totalLength = 0;
        for (KnowledgeRepository.ChunkRecord chunk : corpus) {
            List<String> terms = tokenizer.tokenize(chunk.title() + " " + chunk.content());
            Map<String, Integer> frequencies = new HashMap<>();
            for (String term : terms) {
                frequencies.merge(term, 1, Integer::sum);
            }
            queryTerms.stream().filter(frequencies::containsKey)
                    .forEach(term -> documentFrequency.merge(term, 1, Integer::sum));
            documents.add(new DocumentTerms(chunk, frequencies, terms.size()));
            totalLength += terms.size();
        }
        double averageLength = Math.max(1.0, totalLength / documents.size());
        List<ScoredChunk> scores = new ArrayList<>();
        for (DocumentTerms document : documents) {
            double score = 0;
            for (String term : queryTerms) {
                int tf = document.frequencies().getOrDefault(term, 0);
                if (tf == 0) {
                    continue;
                }
                int df = documentFrequency.getOrDefault(term, 0);
                double idf = Math.log(1.0 + (documents.size() - df + 0.5) / (df + 0.5));
                double denominator = tf + K1 * (1.0 - B + B * document.length() / averageLength);
                score += idf * (tf * (K1 + 1.0)) / denominator;
            }
            if (score > 0) {
                scores.add(new ScoredChunk(document.chunk(), score));
            }
        }
        return scores.stream().sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
                .limit(Math.max(1, topK)).toList();
    }

    public record ScoredChunk(KnowledgeRepository.ChunkRecord chunk, double score) {
    }

    private record DocumentTerms(KnowledgeRepository.ChunkRecord chunk,
                                 Map<String, Integer> frequencies, int length) {
    }
}
