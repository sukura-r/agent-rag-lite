package com.example.agentrag.task;

import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

@Component
public class AgentPerformanceMetrics {
    private final LongAdder completed=new LongAdder(),failed=new LongAdder(),cancelled=new LongAdder();
    private final LongAdder totalDurationMs=new LongAdder(),totalFirstTokenMs=new LongAdder(),firstTokenSamples=new LongAdder();
    private final AtomicInteger active=new AtomicInteger();
    private final ThreadPoolTaskExecutor executor;

    public AgentPerformanceMetrics(ThreadPoolTaskExecutor executor){this.executor=executor;}
    public void started(){active.incrementAndGet();}
    public void completed(long duration,long firstToken){active.decrementAndGet();completed.increment();record(duration,firstToken);}
    public void failed(long duration,long firstToken){active.decrementAndGet();failed.increment();record(duration,firstToken);}
    public void cancelled(long duration,long firstToken){active.decrementAndGet();cancelled.increment();record(duration,firstToken);}
    private void record(long duration,long firstToken){totalDurationMs.add(duration);if(firstToken>=0){firstTokenSamples.increment();totalFirstTokenMs.add(firstToken);}}
    public Snapshot snapshot(){
        long finished=completed.sum()+failed.sum()+cancelled.sum();long first=firstTokenSamples.sum();
        return new Snapshot(active.get(),completed.sum(),failed.sum(),cancelled.sum(),
                finished==0?0:(double)totalDurationMs.sum()/finished,
                first==0?0:(double)totalFirstTokenMs.sum()/first,
                executor.getActiveCount(),executor.getThreadPoolExecutor().getQueue().size(),
                executor.getPoolSize(),executor.getMaxPoolSize());
    }
    public record Snapshot(int activeRequests,long completed,long failed,long cancelled,double averageDurationMs,
                           double averageFirstTokenMs,int executorActiveThreads,int executorQueueSize,
                           int executorPoolSize,int executorMaxPoolSize){}
}
