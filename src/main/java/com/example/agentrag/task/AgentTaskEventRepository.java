package com.example.agentrag.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public class AgentTaskEventRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final int maxEvents;
    private final int retentionDays;
    public AgentTaskEventRepository(JdbcTemplate jdbc,ObjectMapper json,
                                    @Value("${app.agent.event-max-per-task:1000}")int maxEvents,
                                    @Value("${app.agent.event-retention-days:7}")int retentionDays){
        this.jdbc=jdbc;this.json=json;this.maxEvents=Math.max(100,maxEvents);this.retentionDays=Math.max(1,retentionDays);
    }

    @Transactional
    public int append(String taskId,String eventType,Object data){
        Integer next=jdbc.queryForObject("SELECT next_event_seq FROM agent_task_history WHERE task_id=? FOR UPDATE",
                Integer.class,taskId);
        int sequence=next==null?1:next;
        int updated=jdbc.update("UPDATE agent_task_history SET next_event_seq=next_event_seq+1 WHERE task_id=?",taskId);
        if(updated!=1)throw new IllegalStateException("Agent任务不存在，无法分配事件编号："+taskId);
        try{jdbc.update("INSERT INTO agent_task_event(task_id,event_seq,event_type,payload_json) VALUES(?,?,?,?)",
                taskId,sequence,eventType,json.writeValueAsString(data));}
        catch(Exception e){throw new IllegalStateException("保存Agent事件失败",e);}
        jdbc.update("DELETE FROM agent_task_event WHERE task_id=? AND event_seq<=?",taskId,sequence-maxEvents);
        return sequence;
    }

    public int minimumSequence(String userId,String taskId){
        Integer min=jdbc.queryForObject("SELECT COALESCE(MIN(e.event_seq),0) FROM agent_task_event e "
                +"JOIN agent_task_history t ON t.task_id=e.task_id WHERE t.user_id=? AND e.task_id=?",Integer.class,userId,taskId);
        return min==null?0:min;
    }

    @Scheduled(cron="${app.agent.event-cleanup-cron:0 30 3 * * *}")
    public void cleanupExpired(){jdbc.update("DELETE FROM agent_task_event WHERE created_at<DATE_SUB(CURRENT_TIMESTAMP,INTERVAL ? DAY)",retentionDays);}

    public List<EventRecord> findBySession(String userId,String sessionId){
        return jdbc.query("SELECT e.task_id,e.event_seq,e.event_type,e.payload_json,e.created_at "
                        +"FROM agent_task_event e JOIN agent_task_history t ON t.task_id=e.task_id "
                        +"WHERE t.user_id=? AND t.session_id=? AND e.event_type IN ('task','status','answer','done','cancelled','error') "
                        +"ORDER BY t.created_at,e.event_seq",
                (rs,n)->new EventRecord(rs.getString("task_id"),rs.getInt("event_seq"),rs.getString("event_type"),
                        rs.getString("payload_json"),rs.getObject("created_at",LocalDateTime.class)),userId,sessionId);
    }

    public List<EventRecord> findTaskEvents(String userId,String taskId,int afterSequence){
        return jdbc.query("SELECT e.task_id,e.event_seq,e.event_type,e.payload_json,e.created_at "
                        +"FROM agent_task_event e JOIN agent_task_history t ON t.task_id=e.task_id "
                        +"WHERE t.user_id=? AND e.task_id=? AND e.event_seq>? "
                        +"AND e.event_type IN ('task','status','token','answer','done','cancelled','error') "
                        +"ORDER BY e.event_seq",
                (rs,n)->new EventRecord(rs.getString("task_id"),rs.getInt("event_seq"),rs.getString("event_type"),
                        rs.getString("payload_json"),rs.getObject("created_at",LocalDateTime.class)),userId,taskId,afterSequence);
    }

    public record EventRecord(String taskId,int sequence,String eventType,String payloadJson,LocalDateTime createdAt){}
}
