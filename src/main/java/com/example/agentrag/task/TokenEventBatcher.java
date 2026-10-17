package com.example.agentrag.task;

import java.util.function.Consumer;

/** 将模型产生的细粒度文本增量合并，减少数据库写入和SSE事件数量。 */
public final class TokenEventBatcher {
    private final int batchCharacters;
    private final long maxWaitNanos;
    private final Consumer<String> consumer;
    private final StringBuilder pending = new StringBuilder();
    private long firstPendingAt;

    public TokenEventBatcher(int batchCharacters, long maxWaitMillis, Consumer<String> consumer) {
        this.batchCharacters = Math.max(1, batchCharacters);
        this.maxWaitNanos = Math.max(1, maxWaitMillis) * 1_000_000L;
        this.consumer = consumer;
    }

    public synchronized void append(String delta) {
        if (delta == null || delta.isEmpty()) return;
        if (pending.isEmpty()) firstPendingAt = System.nanoTime();
        pending.append(delta);
        if (pending.length() >= batchCharacters || System.nanoTime() - firstPendingAt >= maxWaitNanos) flush();
    }

    public synchronized void flush() {
        if (pending.isEmpty()) return;
        String value = pending.toString();
        pending.setLength(0);
        firstPendingAt = 0;
        consumer.accept(value);
    }
}
