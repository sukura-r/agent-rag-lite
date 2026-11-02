package com.example.agentrag.retrieval;

import com.example.agentrag.knowledge.KnowledgeRepository;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class Bm25Retriever {
    private static final double K1 = 1.2;
    private static final double B = 0.75;
    private final ChineseTokenizer tokenizer;

    public Bm25Retriever(ChineseTokenizer tokenizer) { this.tokenizer = tokenizer; }

    // Ad-hoc evaluations may supply a corpus; online queries reuse an immutable index.
    public List<ScoredChunk> search(String query, List<KnowledgeRepository.ChunkRecord> corpus, int topK) {
        return buildIndex(corpus).search(query, null, topK);
    }

    public SearchIndex buildIndex(List<KnowledgeRepository.ChunkRecord> corpus) {
        Map<String, DocumentTerms> documents = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> postings = new HashMap<>();
        Map<String, CorpusSize> sizes = new HashMap<>();
        long totalLength = 0;
        for (KnowledgeRepository.ChunkRecord chunk : corpus) {
            List<String> terms = tokenizer.tokenize(chunk.title() + " " + chunk.content());
            Map<String, Integer> frequencies = new HashMap<>();
            terms.forEach(term -> frequencies.merge(term, 1, Integer::sum));
            DocumentTerms doc = new DocumentTerms(chunk, terms.size(), documents.size());
            if (documents.putIfAbsent(chunk.id(), doc) != null) {
                throw new IllegalArgumentException("BM25语料包含重复Chunk ID：" + chunk.id());
            }
            frequencies.forEach((term, tf) -> postings.computeIfAbsent(term, ignored -> new HashMap<>()).put(chunk.id(), tf));
            sizes.merge(chunk.documentId(), new CorpusSize(1, terms.size()), CorpusSize::plus);
            totalLength += terms.size();
        }
        Map<String, Map<String, Integer>> immutable = new HashMap<>();
        postings.forEach((term, values) -> immutable.put(term, Map.copyOf(values)));
        return new SearchIndex(Map.copyOf(documents), Map.copyOf(immutable), Map.copyOf(sizes),
                new CorpusSize(documents.size(), totalLength));
    }

    public final class SearchIndex {
        private final Map<String, DocumentTerms> documents;
        private final Map<String, Map<String, Integer>> postings;
        private final Map<String, CorpusSize> sizes;
        private final CorpusSize total;

        private SearchIndex(Map<String, DocumentTerms> documents, Map<String, Map<String, Integer>> postings,
                            Map<String, CorpusSize> sizes, CorpusSize total) {
            this.documents = documents; this.postings = postings; this.sizes = sizes; this.total = total;
        }

        public int chunkCount(Set<String> allowedDocuments) { return corpusSize(allowedDocuments).count(); }

        public List<ScoredChunk> search(String query, Set<String> allowedDocuments, int topK) {
            if (topK <= 0) return List.of();
            CorpusSize corpus = corpusSize(allowedDocuments);
            if (corpus.count() == 0) return List.of();
            double averageLength = Math.max(1.0, (double) corpus.length() / corpus.count());
            Map<String, Double> scores = new HashMap<>();
            for (String term : new TreeSet<>(tokenizer.tokenize(query))) {
                Map<String, Integer> matches = postings.getOrDefault(term, Map.of());
                long df = allowedDocuments == null ? matches.size() : matches.keySet().stream()
                        .filter(id -> allowedDocuments.contains(documents.get(id).chunk().documentId())).count();
                if (df == 0) continue;
                double idf = Math.log(1.0 + (corpus.count() - df + 0.5) / (df + 0.5));
                matches.forEach((id, tf) -> {
                    DocumentTerms document = documents.get(id);
                    if (allowedDocuments != null && !allowedDocuments.contains(document.chunk().documentId())) return;
                    double denominator = tf + K1 * (1.0 - B + B * document.length() / averageLength);
                    scores.merge(id, idf * tf * (K1 + 1.0) / denominator, Double::sum);
                });
            }
            return scores.entrySet().stream()
                    .sorted(Comparator.<Map.Entry<String, Double>>comparingDouble(Map.Entry::getValue).reversed()
                            .thenComparingInt(entry -> documents.get(entry.getKey()).ordinal()))
                    .limit(topK).map(entry -> new ScoredChunk(documents.get(entry.getKey()).chunk(), entry.getValue())).toList();
        }

        private CorpusSize corpusSize(Set<String> allowedDocuments) {
            if (allowedDocuments == null) return total;
            CorpusSize result = new CorpusSize(0, 0);
            for (String id : allowedDocuments) result = result.plus(sizes.getOrDefault(id, new CorpusSize(0, 0)));
            return result;
        }
    }

    public record ScoredChunk(KnowledgeRepository.ChunkRecord chunk, double score) { }
    private record DocumentTerms(KnowledgeRepository.ChunkRecord chunk, int length, int ordinal) { }
    private record CorpusSize(int count, long length) {
        CorpusSize plus(CorpusSize other) { return new CorpusSize(count + other.count, length + other.length); }
    }
}
