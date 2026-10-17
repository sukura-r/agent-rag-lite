package com.example.agentrag.document;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class DocumentParsingProperties {
    public final long maxFileBytes;
    public final int maxCharacters;
    public final int maxChunks;
    public final int maxPdfPages;
    public final int timeoutSeconds;
    public final int concurrency;
    public final int maxAttempts;
    public final int leaseSeconds;
    public final String parserVersion;

    public DocumentParsingProperties(
            @Value("${app.document.parsing.max-file-bytes:20971520}") long maxFileBytes,
            @Value("${app.document.parsing.max-characters:200000}") int maxCharacters,
            @Value("${app.document.parsing.max-chunks:1000}") int maxChunks,
            @Value("${app.document.parsing.max-pdf-pages:300}") int maxPdfPages,
            @Value("${app.document.parsing.timeout-seconds:60}") int timeoutSeconds,
            @Value("${app.document.parsing.concurrency:2}") int concurrency,
            @Value("${app.document.parsing.max-attempts:3}") int maxAttempts,
            @Value("${app.document.parsing.lease-seconds:120}") int leaseSeconds,
            @Value("${app.document.parsing.parser-version:tika-3.3.2-markdown-v1}") String parserVersion) {
        if (maxFileBytes <= 0 || maxCharacters <= 0 || maxChunks <= 0 || maxPdfPages <= 0 || timeoutSeconds <= 0) {
            throw new IllegalArgumentException("文档解析限制必须为正数");
        }
        this.maxFileBytes = maxFileBytes;
        this.maxCharacters = maxCharacters;
        this.maxChunks = maxChunks;
        this.maxPdfPages = maxPdfPages;
        this.timeoutSeconds = timeoutSeconds;
        this.concurrency = Math.max(1, concurrency);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.leaseSeconds = Math.max(timeoutSeconds + 10, leaseSeconds);
        this.parserVersion = parserVersion;
    }
}
