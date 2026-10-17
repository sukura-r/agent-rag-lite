package com.example.agentrag.task;

/** 同一会话已有回答任务时拒绝再次提交，避免问答顺序交叉。 */
public class SessionBusyException extends RuntimeException {
    public SessionBusyException(String message) {
        super(message);
    }
}
