package com.example.agentrag.document;

import com.example.agentrag.knowledge.TextChunker;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.InetAddress;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class DocumentParsingWorker {
    private static final Logger log = LoggerFactory.getLogger(DocumentParsingWorker.class);
    private final DocumentIngestionRepository repository;
    private final DocumentObjectStorage storage;
    private final DocumentFileParser parser;
    private final TextChunker chunker;
    private final DocumentParsingProperties properties;
    private final ThreadPoolExecutor workers;
    private final ThreadPoolExecutor parsers;
    private final String workerId;

    public DocumentParsingWorker(DocumentIngestionRepository repository, DocumentObjectStorage storage,
                                 DocumentFileParser parser, TextChunker chunker, DocumentParsingProperties properties) {
        this.repository = repository;
        this.storage = storage;
        this.parser = parser;
        this.chunker = chunker;
        this.properties = properties;
        this.workers = executor("document-task-", properties.concurrency, properties.concurrency * 4);
        this.parsers = executor("document-parser-", properties.concurrency, properties.concurrency * 2);
        this.workerId = host() + "-" + ProcessHandle.current().pid();
    }

    @Scheduled(fixedDelayString = "${app.document.parsing.poll-delay-ms:1000}")
    public void poll() {
        int capacity = Math.max(0, workers.getMaximumPoolSize() - workers.getActiveCount()
                + workers.getQueue().remainingCapacity());
        if (capacity == 0) return;
        for (String taskId : repository.dueTaskIds(Math.min(capacity, properties.concurrency * 2))) {
            if (!repository.claim(taskId, workerId, properties.leaseSeconds)) continue;
            try { workers.execute(() -> process(taskId)); }
            catch (RuntimeException rejected) { log.warn("提交文档解析任务失败，等待租约恢复，taskId={}", taskId, rejected); }
        }
    }

    private void process(String taskId) {
        DocumentIngestionRepository.ParseWork work = repository.loadWork(taskId);
        Future<DocumentFileParser.ParsedDocument> future = parsers.submit(() -> {
            try (InputStream input = storage.open(work.objectKey())) {
                return parser.parse(input, work.filename(), work.mediaType());
            }
        });
        try {
            DocumentFileParser.ParsedDocument parsed = future.get(properties.timeoutSeconds, TimeUnit.SECONDS);
            List<String> chunks = chunker.split(parsed.markdown());
            if (chunks.isEmpty()) throw new DocumentParsingException("EMPTY_DOCUMENT", "文件中没有可索引文本", false);
            if (chunks.size() > properties.maxChunks) {
                throw new DocumentParsingException("TOO_MANY_CHUNKS", "分块数量超过" + properties.maxChunks, false);
            }
            repository.complete(work, parsed.markdown(), chunks, properties.parserVersion);
        } catch (TimeoutException exception) {
            future.cancel(true);
            handleFailure(work, new DocumentParsingException("PARSE_TIMEOUT", "解析超过" + properties.timeoutSeconds + "秒", true, exception));
        } catch (Exception exception) {
            future.cancel(true);
            Throwable cause = exception instanceof java.util.concurrent.ExecutionException && exception.getCause() != null
                    ? exception.getCause() : exception;
            DocumentParsingException parsed = cause instanceof DocumentParsingException value ? value
                    : new DocumentParsingException("PARSE_ERROR", cause.getMessage(), true, cause);
            handleFailure(work, parsed);
        }
    }

    private void handleFailure(DocumentIngestionRepository.ParseWork work, DocumentParsingException exception) {
        if (!exception.retryable() || work.attempts() >= properties.maxAttempts) {
            repository.failPermanently(work, exception.errorCode(), exception.getMessage());
            log.warn("文档解析永久失败，documentId={}, version={}, code={}", work.documentId(), work.version(), exception.errorCode());
            return;
        }
        int[] delays = {5, 30, 120};
        int delay = delays[Math.min(Math.max(0, work.attempts() - 1), delays.length - 1)];
        repository.retryLater(work, exception.errorCode(), exception.getMessage(), LocalDateTime.now().plusSeconds(delay));
    }

    @Scheduled(fixedDelay = 60000)
    public void cleanupDeletedSources() {
        for (var source : repository.pendingDeletes(20)) try {
            storage.remove(source.objectKey());
            repository.markDeleted(source);
        } catch (Exception exception) {
            repository.markDeleteFailed(source, exception.getMessage());
        }
    }

    @PreDestroy public void stop() { workers.shutdownNow(); parsers.shutdownNow(); }

    private ThreadPoolExecutor executor(String prefix, int threads, int queue) {
        java.util.concurrent.atomic.AtomicInteger sequence = new java.util.concurrent.atomic.AtomicInteger();
        return new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(Math.max(1, queue)),
                runnable -> { Thread thread = new Thread(runnable, prefix + sequence.incrementAndGet()); thread.setDaemon(true); return thread; },
                new ThreadPoolExecutor.AbortPolicy());
    }

    private String host() {
        try { return InetAddress.getLocalHost().getHostName(); } catch (Exception ignored) { return "unknown-host"; }
    }
}
