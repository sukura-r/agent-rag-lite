package com.example.agentrag.task;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AgentTaskHistoryRepository {
    private final JdbcTemplate jdbc;
    public AgentTaskHistoryRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public void create(String taskId,String userId,String sessionId){jdbc.update(
            "INSERT INTO agent_task_history(task_id,user_id,session_id,status) VALUES(?,?,?,'RUNNING')",taskId,userId,sessionId);}
    public void status(String taskId,String status,String error){jdbc.update(
            "UPDATE agent_task_history SET status=?,error_message=? WHERE task_id=?",status,trim(error),taskId);}
    public String status(String taskId,String userId){return jdbc.query(
            "SELECT status FROM agent_task_history WHERE task_id=? AND user_id=?",(rs,n)->rs.getString(1),taskId,userId)
            .stream().findFirst().orElse(null);}
    public String statusAnyOwner(String taskId){return jdbc.query("SELECT status FROM agent_task_history WHERE task_id=?",
            (rs,n)->rs.getString(1),taskId).stream().findFirst().orElse(null);}
    private String trim(String v){if(v==null)return null;return v.substring(0,Math.min(1000,v.length()));}
}
