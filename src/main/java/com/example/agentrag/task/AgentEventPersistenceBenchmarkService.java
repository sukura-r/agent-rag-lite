package com.example.agentrag.task;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Service
public class AgentEventPersistenceBenchmarkService {
    private final JdbcTemplate jdbc;
    private final AgentTaskEventRepository events;

    public AgentEventPersistenceBenchmarkService(JdbcTemplate jdbc, AgentTaskEventRepository events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    public Report run(int requestedTasks, int requestedDeltas, int requestedBatchCharacters) {
        int tasks = Math.max(1, Math.min(requestedTasks, 32));
        int deltas = Math.max(10, Math.min(requestedDeltas, 2000));
        int batchCharacters = Math.max(2, Math.min(requestedBatchCharacters, 100));
        Result direct = execute("逐Token写入", tasks, deltas, 1);
        Result batched = execute("批量合并写入", tasks, deltas, batchCharacters);
        SequenceSafety sequenceSafety = verifyConcurrentSequenceAllocation(Math.min(tasks, 8), Math.min(deltas, 100));
        double writeReduction = direct.databaseWrites() == 0 ? 0
                : 1.0 - (double) batched.databaseWrites() / direct.databaseWrites();
        double speedup = batched.durationMillis() == 0 ? 0
                : (double) direct.durationMillis() / batched.durationMillis();
        return new Report(tasks, deltas, batchCharacters, direct, batched, sequenceSafety, writeReduction, speedup,
                "该基准只测真实MySQL事件持久化层，不代表完整Agent、模型或网络吞吐");
    }

    private SequenceSafety verifyConcurrentSequenceAllocation(int writers, int eventsPerWriter) {
        String taskId = id();
        ExecutorService executor = Executors.newFixedThreadPool(writers);
        try {
            jdbc.update("INSERT INTO agent_task_history(task_id,user_id,session_id,status) VALUES(?,?,?,'RUNNING')",
                    taskId, "benchmark-user", "benchmark-sequence-session");
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int writer = 0; writer < writers; writer++) {
                int writerNo = writer;
                futures.add(executor.submit(() -> {
                    start.await();
                    for (int i = 0; i < eventsPerWriter; i++)
                        events.append(taskId, "token", Map.of("delta", "字", "writer", writerNo));
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) future.get();
            Map<String,Object> row = jdbc.queryForMap("SELECT COUNT(*) total_count,COUNT(DISTINCT event_seq) distinct_count,"
                    +"MIN(event_seq) min_seq,MAX(event_seq) max_seq FROM agent_task_event WHERE task_id=?", taskId);
            int expected = writers * eventsPerWriter;
            int total = ((Number)row.get("total_count")).intValue();
            int distinct = ((Number)row.get("distinct_count")).intValue();
            int min = ((Number)row.get("min_seq")).intValue();
            int max = ((Number)row.get("max_seq")).intValue();
            return new SequenceSafety(writers, eventsPerWriter, expected, total, distinct, min, max,
                    total==expected&&distinct==expected&&min==1&&max==expected);
        } catch (Exception exception) {
            throw new IllegalStateException("Agent事件编号并发验收失败", exception);
        } finally {
            executor.shutdownNow();
            jdbc.update("DELETE FROM agent_task_event WHERE task_id=?", taskId);
            jdbc.update("DELETE FROM agent_task_history WHERE task_id=?", taskId);
        }
    }

    private Result execute(String mode, int tasks, int deltas, int batchCharacters) {
        List<String> taskIds = new ArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(tasks, 8));
        try {
            for (int i = 0; i < tasks; i++) {
                String taskId = id();
                taskIds.add(taskId);
                jdbc.update("INSERT INTO agent_task_history(task_id,user_id,session_id,status) VALUES(?,?,?,'RUNNING')",
                        taskId, "benchmark-user", "benchmark-session");
            }
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (String taskId : taskIds) {
                futures.add(executor.submit(() -> {
                    start.await();
                    if (batchCharacters == 1) {
                        for (int i = 0; i < deltas; i++) events.append(taskId, "token", Map.of("delta", "字"));
                        return deltas;
                    }
                    int[] writes = {0};
                    TokenEventBatcher batcher = new TokenEventBatcher(batchCharacters, 60_000,
                            delta -> { events.append(taskId, "token", Map.of("delta", delta)); writes[0]++; });
                    for (int i = 0; i < deltas; i++) batcher.append("字");
                    batcher.flush();
                    return writes[0];
                }));
            }
            Instant started = Instant.now();
            start.countDown();
            int writes = 0;
            for (Future<Integer> future : futures) writes += future.get();
            long duration = Duration.between(started, Instant.now()).toMillis();
            return new Result(mode, duration, writes, (long) tasks * deltas,
                    duration == 0 ? 0 : writes * 1000.0 / duration);
        } catch (Exception exception) {
            throw new IllegalStateException("Agent事件持久化基准失败", exception);
        } finally {
            executor.shutdownNow();
            for (String taskId : taskIds) {
                jdbc.update("DELETE FROM agent_task_event WHERE task_id=?", taskId);
                jdbc.update("DELETE FROM agent_task_history WHERE task_id=?", taskId);
            }
        }
    }

    private String id() { return UUID.randomUUID().toString().replace("-", ""); }

    public record Report(int concurrentTasks, int tokenDeltasPerTask, int batchCharacters,
                         Result direct, Result batched, SequenceSafety sequenceSafety, double databaseWriteReduction,
                         double persistenceSpeedup, String boundary) { }
    public record Result(String mode, long durationMillis, int databaseWrites,
                         long originalTokenDeltas, double writesPerSecond) { }
    public record SequenceSafety(int concurrentWriters, int eventsPerWriter, int expectedEvents,
                                 int actualEvents, int distinctSequences, int minimumSequence,
                                 int maximumSequence, boolean passed) { }
}
