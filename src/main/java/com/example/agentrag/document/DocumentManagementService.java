package com.example.agentrag.document;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DocumentManagementService {
    private final DocumentIngestionRepository repository;
    private final DocumentObjectStorage storage;
    private final DocumentParsingProperties properties;

    public DocumentManagementService(DocumentIngestionRepository repository, DocumentObjectStorage storage,
                                     DocumentParsingProperties properties) {
        this.repository = repository;
        this.storage = storage;
        this.properties = properties;
    }

    public List<DocumentView> list(int page, int size) {
        int safeSize = Math.max(1, Math.min(100, size));
        return repository.list(safeSize, Math.max(0, page) * safeSize);
    }

    public int count() { return repository.countVisible(); }

    public DocumentDetail find(String documentId) {
        return new DocumentDetail(repository.find(documentId), repository.versions(documentId));
    }

    public String retry(String documentId, int version) {
        if (!repository.retry(documentId, version)) throw new IllegalArgumentException("当前解析任务不能重试");
        return "PENDING";
    }

    public Download download(String documentId, int version) {
        var source = repository.source(documentId, version);
        return new Download(source.filename(), source.mediaType(), storage.read(source.objectKey(), properties.maxFileBytes));
    }

    public record Download(String filename, String mediaType, byte[] content) { }
    public record DocumentDetail(DocumentView document, List<DocumentVersionView> versions) { }
}
