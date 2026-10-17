package com.example.agentrag.model;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.util.concurrent.CancellationException;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class ModelCallRetrier {

    private final int maxAttempts;
    private final long backoffMillis;
    private final int failureThreshold;
    private final long openMillis;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong openUntil = new AtomicLong();

    @Autowired
    public ModelCallRetrier(@Value("${app.model.max-attempts:2}") int maxAttempts,
                            @Value("${app.model.retry-backoff-ms:300}") long backoffMillis) {
        this(maxAttempts, backoffMillis, 3, 30000);
    }

    ModelCallRetrier(int maxAttempts,long backoffMillis,int failureThreshold,long openMillis) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.backoffMillis = Math.max(0, backoffMillis);
        this.failureThreshold=Math.max(1,failureThreshold);
        this.openMillis=Math.max(1000,openMillis);
    }

    public <T> T execute(Supplier<T> operation) {
        if(System.currentTimeMillis()<openUntil.get()) throw new IllegalStateException("模型服务熔断中，请稍后重试");
        RuntimeException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("模型调用已取消");
            }
            try {
                T result=operation.get();consecutiveFailures.set(0);openUntil.set(0);return result;
            } catch (RuntimeException exception) {
                last = exception;
                if (attempt == maxAttempts || !isRetryable(exception)) {
                    recordFailure(exception);
                    throw exception;
                }
                sleep(backoffMillis * attempt);
            }
        }
        throw last == null ? new IllegalStateException("模型调用失败") : last;
    }

    private void recordFailure(RuntimeException exception){if(!isRetryable(exception))return;
        if(consecutiveFailures.incrementAndGet()>=failureThreshold)openUntil.set(System.currentTimeMillis()+openMillis);}

    private boolean isRetryable(RuntimeException exception) {
        if (exception instanceof ResourceAccessException) {
            return true;
        }
        if (exception instanceof RestClientResponseException responseException) {
            int status = responseException.getStatusCode().value();
            return status == 429 || status >= 500;
        }
        return false;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CancellationException("模型重试等待被取消");
        }
    }
}
