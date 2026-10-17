package com.example.agentrag.task;

import com.example.agentrag.agent.AgentService;
import com.example.agentrag.conversation.ConversationService;
import com.example.agentrag.security.AuthenticatedUser;
import com.example.agentrag.security.PromptSecurityService;
import com.example.agentrag.security.SecurityAuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Comparator;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class AgentStreamService {
    private final AgentService agentService;
    private final ConversationService conversationService;
    private final AgentTaskRegistry taskRegistry;
    private final Executor executor;
    private final long streamTimeoutMillis;
    private final PromptSecurityService promptSecurity;
    private final SecurityAuditService auditService;
    private final AgentTaskHistoryRepository taskHistory;
    private final AgentPerformanceMetrics performanceMetrics;
    private final AgentTaskEventRepository eventRepository;
    private final ObjectMapper json;
    private final Semaphore admission;
    private final int tokenBatchCharacters;
    private final long tokenBatchMaxWaitMillis;
    private final Map<String,Future<?>> futures=new ConcurrentHashMap<>();
    private final Map<String,TaskControl> controls=new ConcurrentHashMap<>();
    private final Map<String,CopyOnWriteArrayList<Subscriber>> subscribers=new ConcurrentHashMap<>();
    private final Map<String,String> activeSessionTasks=new ConcurrentHashMap<>();
    private final Map<String,Long> taskStartedAt=new ConcurrentHashMap<>();

    public AgentStreamService(AgentService agentService,ConversationService conversationService,
                              AgentTaskRegistry taskRegistry,PromptSecurityService promptSecurity,
                              SecurityAuditService auditService,AgentTaskHistoryRepository taskHistory,
                              AgentPerformanceMetrics performanceMetrics,AgentTaskEventRepository eventRepository,
                              ObjectMapper json,@Qualifier("agentTaskExecutor")Executor executor,
                              @Value("${app.agent.stream-timeout-ms:600000}")long streamTimeoutMillis,
                              @Value("${app.agent.max-concurrent-tasks:4}")int maxConcurrentTasks,
                              @Value("${app.agent.token-batch-characters:12}")int tokenBatchCharacters,
                              @Value("${app.agent.token-batch-max-wait-ms:80}")long tokenBatchMaxWaitMillis){
        this.agentService=agentService;this.conversationService=conversationService;this.taskRegistry=taskRegistry;
        this.promptSecurity=promptSecurity;this.auditService=auditService;this.taskHistory=taskHistory;
        this.performanceMetrics=performanceMetrics;this.eventRepository=eventRepository;this.json=json;this.executor=executor;
        this.streamTimeoutMillis=Math.max(1000,streamTimeoutMillis);this.admission=new Semaphore(Math.max(1,maxConcurrentTasks));
        this.tokenBatchCharacters=Math.max(1,tokenBatchCharacters);
        this.tokenBatchMaxWaitMillis=Math.max(1,tokenBatchMaxWaitMillis);
    }

    public TaskCreated createTask(AuthenticatedUser user,String requestedSessionId,String message){
        if(!admission.tryAcquire())throw new SystemOverloadedException("Agent并发任务已达到上限，请稍后重试");
        boolean submitted=false,metricsStarted=false,sessionLocked=false;String taskId=null,sessionId=null;
        try{
            String safeMessage;
            try{safeMessage=promptSecurity.validateAndRedactUserInput(message);}
            catch(RuntimeException e){auditService.record(user.userId(),"PROMPT_INJECTION","agent_task","DENY",e.getMessage(),Map.of());throw e;}
            sessionId=requestedSessionId==null||requestedSessionId.isBlank()
                    ?conversationService.create(user.userId(),safeMessage):requestedSessionId;
            conversationService.requireSession(user.userId(),sessionId);
            String lockTaskId=id();
            String existingTask=activeSessionTasks.putIfAbsent(sessionId,lockTaskId);
            if(existingTask!=null)throw new SessionBusyException("当前会话正在生成回答，请等待完成后再提问；如需并行，请新建会话");
            taskId=lockTaskId;sessionLocked=true;
            conversationService.captureMemory(user.userId(),safeMessage);
            List<AgentService.HistoryMessage> history=conversationService.context(user.userId(),sessionId,safeMessage);
            conversationService.append(user.userId(),sessionId,"user",safeMessage);
            String finalTaskId=taskId;String finalSessionId=sessionId;
            taskHistory.create(taskId,user.userId(),sessionId);taskRegistry.running(taskId,user.userId());
            taskStartedAt.put(taskId,System.currentTimeMillis());
            TaskControl control=new TaskControl(sessionId);controls.put(taskId,control);
            FutureTask<Void> future=new FutureTask<>(()->{
                if(!control.state.compareAndSet(0,1))return null;
                runTask(finalTaskId,user,finalSessionId,safeMessage,history,control);return null;
            });
            futures.put(taskId,future);performanceMetrics.started();metricsStarted=true;executor.execute(future);submitted=true;
            return new TaskCreated(taskId,sessionId,"RUNNING");
        }catch(RejectedExecutionException e){
            if(taskId!=null){taskRegistry.failed(taskId);taskHistory.status(taskId,"FAILED","任务队列已满");futures.remove(taskId);}
            if(metricsStarted)performanceMetrics.failed(0,-1);
            throw new SystemOverloadedException("Agent任务队列已满，请稍后重试");
        }finally{
            if(!submitted){
                admission.release();
                if(sessionLocked&&sessionId!=null&&taskId!=null)activeSessionTasks.remove(sessionId,taskId);
                if(taskId!=null){taskStartedAt.remove(taskId);controls.remove(taskId);futures.remove(taskId);}
            }
        }
    }

    public SseEmitter stream(AuthenticatedUser user,String requestedSessionId,String message){
        TaskCreated created=createTask(user,requestedSessionId,message);return subscribe(user.userId(),created.taskId(),0);
    }

    public SseEmitter subscribe(String userId,String taskId,int afterSequence){
        String status=taskHistory.status(taskId,userId);
        if(status==null)throw new IllegalArgumentException("任务不存在或无权访问："+taskId);
        int minimum=eventRepository.minimumSequence(userId,taskId);
        if(afterSequence>0&&minimum>0&&afterSequence<minimum-1)
            throw new IllegalArgumentException("请求的事件历史已过期，最早可用事件编号为"+minimum);
        SseEmitter emitter=new SseEmitter(streamTimeoutMillis);Subscriber subscriber=new Subscriber(emitter,Math.max(0,afterSequence));
        subscribers.computeIfAbsent(taskId,ignored->new CopyOnWriteArrayList<>()).add(subscriber);
        Runnable remove=()->removeSubscriber(taskId,subscriber);
        emitter.onCompletion(remove);emitter.onTimeout(()->{remove.run();emitter.complete();});emitter.onError(error->remove.run());
        for(var event:eventRepository.findTaskEvents(userId,taskId,afterSequence))sendPersisted(taskId,subscriber,event);
        if(isTerminal(taskHistory.status(taskId,userId)))emitter.complete();
        return emitter;
    }

    public String cancel(String userId,String taskId){
        if(taskHistory.status(taskId,userId)==null)throw new IllegalArgumentException("任务不存在或无权访问："+taskId);
        return cancelOwnedTask(taskId);
    }

    private String cancelOwnedTask(String taskId){
        String current=taskRegistry.status(taskId);if(current==null)current=taskHistory.statusAnyOwner(taskId);
        if(current==null)throw new IllegalArgumentException("任务不存在："+taskId);
        if(!current.equals("RUNNING")&&!current.equals("CANCEL_REQUESTED"))return current;
        taskRegistry.requestCancel(taskId);Future<?> future=futures.get(taskId);TaskControl control=controls.get(taskId);
        boolean cancelAccepted=future!=null&&future.cancel(true);
        taskRegistry.cancelled(taskId);taskHistory.status(taskId,"CANCELLED",null);
        // 取消确认后立即允许同一会话创建替代任务；旧线程仍保留并发名额，直到真正退出。
        if(control!=null)activeSessionTasks.remove(control.sessionId,taskId);
        if(cancelAccepted&&control!=null&&control.state.compareAndSet(0,2)){
            try{publish(taskId,"cancelled",Map.of("taskId",taskId,"status","CANCELLED"));}
            finally{cleanupTaskResources(taskId,control.sessionId);performanceMetrics.cancelled(0,-1);}
        }
        return "CANCELLED";
    }

    public String status(String userId,String taskId){
        String cachedOwner=taskRegistry.owner(taskId);
        if(cachedOwner!=null){
            if(!cachedOwner.equals(userId))throw new IllegalArgumentException("任务不存在或无权访问："+taskId);
            String cachedStatus=taskRegistry.status(taskId);
            if(cachedStatus!=null)return cachedStatus;
        }
        String persisted=taskHistory.status(taskId,userId);
        if(persisted==null)throw new IllegalArgumentException("任务不存在或无权访问："+taskId);
        taskRegistry.restore(taskId,userId,persisted);
        return persisted;
    }

    /**
     * 返回当前Java实例真正仍持有Future的运行任务，避免把服务重启前遗留的RUNNING状态误认为仍在执行。
     */
    public List<RunningTask> runningTasks(String userId){
        return activeSessionTasks.entrySet().stream()
                .filter(entry->futures.containsKey(entry.getValue()))
                .map(entry->{
                    String taskId=entry.getValue();
                    String status=taskHistory.status(taskId,userId);
                    return status==null?null:new RunningTask(taskId,entry.getKey(),status);
                })
                .filter(task->task!=null&&("RUNNING".equals(task.status())||"CANCEL_REQUESTED".equals(task.status())))
                .sorted(Comparator.comparingLong((RunningTask task)->taskStartedAt.getOrDefault(task.taskId(),0L)).reversed())
                .toList();
    }

    private void runTask(String taskId,AuthenticatedUser user,String sessionId,String message,List<AgentService.HistoryMessage> history,
                         TaskControl control){
        long startedAt=System.currentTimeMillis();AtomicLong firstTokenAt=new AtomicLong(-1);String outcome="FAILED";
        TokenEventBatcher tokenBatcher=new TokenEventBatcher(tokenBatchCharacters,tokenBatchMaxWaitMillis,delta->{
            firstTokenAt.compareAndSet(-1,System.currentTimeMillis());
            publish(taskId,"token",Map.of("delta",delta));
        });
        try{
            publish(taskId,"task",Map.of("taskId",taskId,"sessionId",sessionId));
            AgentService.AgentObserver observer=new AgentService.AgentObserver(){
                public void onPhase(String phase){publish(taskId,"status",Map.of("phase",phase));}
                public void beforeModelCall(int iteration){ }
                // 工具名、参数和返回内容只保留在服务端审计与Agent内部步骤中，不发给普通用户。
                public void afterToolCall(int iteration,String toolName,Map<String,Object> arguments,String observationPreview){ }
                public boolean isCancellationRequested(){return taskRegistry.isCancellationRequested(taskId);}
                public boolean streamModelOutput(){return true;}
                public void onAnswerDelta(String delta){tokenBatcher.append(delta);}
            };
            AgentService.AgentAnswer answer=agentService.chat(message,history,observer,user);
            tokenBatcher.flush();
            conversationService.append(user.userId(),sessionId,"assistant",answer.answer());
            publish(taskId,"answer",answer.userView());
            taskRegistry.completed(taskId);taskHistory.status(taskId,"COMPLETED",null);publish(taskId,"done",Map.of("taskId",taskId,"status","COMPLETED"));outcome="COMPLETED";
        }catch(CancellationException e){
            tokenBatcher.flush();
            taskRegistry.cancelled(taskId);taskHistory.status(taskId,"CANCELLED",null);publish(taskId,"cancelled",Map.of("taskId",taskId,"status","CANCELLED"));outcome="CANCELLED";
        }catch(Exception e){
            tokenBatcher.flush();
            if(taskRegistry.isCancellationRequested(taskId)||Thread.currentThread().isInterrupted()){
                taskRegistry.cancelled(taskId);taskHistory.status(taskId,"CANCELLED",null);publish(taskId,"cancelled",Map.of("taskId",taskId,"status","CANCELLED"));outcome="CANCELLED";
            }else{
                taskRegistry.failed(taskId);taskHistory.status(taskId,"FAILED",e.getMessage());publish(taskId,"error",Map.of("taskId",taskId,"message",e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()));
            }
        }finally{
            if(control.state.compareAndSet(1,2))cleanupTaskResources(taskId,sessionId);
            long duration=System.currentTimeMillis()-startedAt;
            long first=firstTokenAt.get()<0?-1:firstTokenAt.get()-startedAt;
            if("COMPLETED".equals(outcome))performanceMetrics.completed(duration,first);else if("CANCELLED".equals(outcome))performanceMetrics.cancelled(duration,first);else performanceMetrics.failed(duration,first);
        }
    }

    private void publish(String taskId,String event,Object data){
        int sequence=eventRepository.append(taskId,event,data);var list=subscribers.getOrDefault(taskId,new CopyOnWriteArrayList<>());
        for(Subscriber subscriber:list)send(taskId,subscriber,sequence,event,data);
        if(isTerminalEvent(event))for(Subscriber subscriber:list)subscriber.emitter().complete();
    }

    private void sendPersisted(String taskId,Subscriber subscriber,AgentTaskEventRepository.EventRecord event){
        try{send(taskId,subscriber,event.sequence(),event.eventType(),json.readTree(event.payloadJson()));}
        catch(Exception e){removeSubscriber(taskId,subscriber);subscriber.emitter().completeWithError(e);}
    }

    private void send(String taskId,Subscriber subscriber,int sequence,String event,Object data){
        while(true){int previous=subscriber.lastSent().get();if(sequence<=previous)return;if(subscriber.lastSent().compareAndSet(previous,sequence))break;}
        try{subscriber.emitter().send(SseEmitter.event().id(String.valueOf(sequence)).name(event).data(data));}
        catch(IOException|IllegalStateException e){removeSubscriber(taskId,subscriber);subscriber.emitter().complete();}
    }

    @Scheduled(fixedDelayString="${app.agent.heartbeat-interval-ms:15000}")
    public void sendHeartbeats(){
        long now=System.currentTimeMillis();
        subscribers.forEach((taskId,list)->list.forEach(subscriber->{
            try{subscriber.emitter().send(SseEmitter.event().name("heartbeat").data(Map.of("time",now)));}
            catch(IOException|IllegalStateException e){removeSubscriber(taskId,subscriber);subscriber.emitter().complete();}
        }));
    }

    private void removeSubscriber(String taskId,Subscriber subscriber){var list=subscribers.get(taskId);if(list==null)return;list.remove(subscriber);if(list.isEmpty())subscribers.remove(taskId,list);}
    private void cleanupTaskResources(String taskId,String sessionId){
        futures.remove(taskId);controls.remove(taskId);activeSessionTasks.remove(sessionId,taskId);
        taskStartedAt.remove(taskId);admission.release();
    }
    private boolean isTerminal(String status){return "COMPLETED".equals(status)||"FAILED".equals(status)||"CANCELLED".equals(status);}
    private boolean isTerminalEvent(String event){return "done".equals(event)||"error".equals(event)||"cancelled".equals(event);}
    private String id(){return UUID.randomUUID().toString().replace("-","");}
    public record TaskCreated(String taskId,String sessionId,String status){}
    public record RunningTask(String taskId,String sessionId,String status){}
    private static final class TaskControl{
        private final String sessionId;
        private final AtomicInteger state=new AtomicInteger(0); // 0等待执行，1正在执行，2已清理
        private TaskControl(String sessionId){this.sessionId=sessionId;}
    }
    private record Subscriber(SseEmitter emitter,AtomicInteger lastSent){Subscriber(SseEmitter emitter,int last){this(emitter,new AtomicInteger(last));}}
}
