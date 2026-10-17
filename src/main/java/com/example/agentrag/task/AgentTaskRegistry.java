package com.example.agentrag.task;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class AgentTaskRegistry {

    private static final Duration TTL = Duration.ofHours(1);
    private static final String PREFIX = "agent:task:";
    private static final String OWNER_SUFFIX = ":owner";

    private final StringRedisTemplate redisTemplate;

    public AgentTaskRegistry(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void running(String taskId, String userId) {
        set(taskId, "RUNNING");
        redisTemplate.opsForValue().set(key(taskId) + OWNER_SUFFIX, userId, TTL);
    }

    public void requestCancel(String taskId) {
        set(taskId, "CANCEL_REQUESTED");
    }

    public boolean isCancellationRequested(String taskId) {
        String status = status(taskId);
        return "CANCEL_REQUESTED".equals(status) || "CANCELLED".equals(status);
    }

    public void completed(String taskId) {
        set(taskId, "COMPLETED");
    }

    public void cancelled(String taskId) {
        set(taskId, "CANCELLED");
    }

    public void failed(String taskId) {
        set(taskId, "FAILED");
    }

    public String status(String taskId) {
        return redisTemplate.opsForValue().get(key(taskId));
    }

    public String owner(String taskId) {
        return redisTemplate.opsForValue().get(key(taskId) + OWNER_SUFFIX);
    }

    public void restore(String taskId,String userId,String status){
        set(taskId,status);
        redisTemplate.opsForValue().set(key(taskId)+OWNER_SUFFIX,userId,TTL);
    }

    public void requireOwner(String taskId, String userId) {
        String owner = owner(taskId);
        if (owner == null || !owner.equals(userId)) {
            throw new IllegalArgumentException("任务不存在或无权访问：" + taskId);
        }
    }

    private void set(String taskId, String status) {
        redisTemplate.opsForValue().set(key(taskId), status, TTL);
    }

    private String key(String taskId) {
        return PREFIX + taskId;
    }
}
