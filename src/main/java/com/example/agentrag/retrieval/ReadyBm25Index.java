package com.example.agentrag.retrieval;

import com.example.agentrag.knowledge.KnowledgeRepository;
import org.springframework.stereotype.Component;
import java.util.List;

/** Immutable index of published versions; refresh on updates/deletes without a stale TTL. */
@Component
public class ReadyBm25Index {
    private final KnowledgeRepository repository;
    private final Bm25Retriever retriever;
    private volatile Snapshot current;

    public ReadyBm25Index(KnowledgeRepository repository, Bm25Retriever retriever) {
        this.repository = repository; this.retriever = retriever;
    }

    public Snapshot snapshot() {
        List<KnowledgeRepository.ReadyVersion> versions = repository.findReadyVersions();
        Snapshot found = current;
        if (found != null && found.versions().equals(versions)) return found;
        synchronized (this) {
            versions = repository.findReadyVersions();
            found = current;
            if (found != null && found.versions().equals(versions)) return found;
            KnowledgeRepository.ReadyCorpus corpus = repository.loadReadyCorpus();
            Snapshot rebuilt = new Snapshot(corpus.versions(), retriever.buildIndex(corpus.chunks()));
            current = rebuilt;
            return rebuilt;
        }
    }

    public record Snapshot(List<KnowledgeRepository.ReadyVersion> versions, Bm25Retriever.SearchIndex index) {
        public Snapshot { versions = List.copyOf(versions); }
    }
}
