package com.example.agentrag.indexing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class IndexTaskRepository {
    private final JdbcTemplate jdbc;

    public IndexTaskRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<OutboxEvent> pendingEvents(int limit) {
        return jdbc.query("""
                SELECT id,aggregate_id,aggregate_version,event_type,payload_json,attempt_count,created_at
                FROM outbox_event WHERE status='PENDING' AND next_attempt_at<=CURRENT_TIMESTAMP
                ORDER BY created_at LIMIT ?
                """, (rs, n) -> new OutboxEvent(rs.getString("id"), rs.getString("aggregate_id"),
                rs.getInt("aggregate_version"), rs.getString("event_type"), rs.getString("payload_json"),
                rs.getInt("attempt_count"), rs.getObject("created_at", LocalDateTime.class)), limit);
    }

    public void markSent(String eventId) {
        jdbc.update("UPDATE outbox_event SET status='SENT',sent_at=CURRENT_TIMESTAMP,last_error=NULL WHERE id=? AND status='PENDING'", eventId);
        jdbc.update("UPDATE document_index_task t JOIN outbox_event e ON e.aggregate_id=t.document_id AND e.aggregate_version=t.version_no "
                + "SET t.status='DISPATCHED' WHERE e.id=? AND t.status='PENDING'", eventId);
    }

    public void markSendFailed(String eventId, String error) {
        jdbc.update("UPDATE outbox_event SET attempt_count=attempt_count+1,last_error=?,"
                        + "next_attempt_at=DATE_ADD(CURRENT_TIMESTAMP, INTERVAL LEAST(60, POW(2,attempt_count)) SECOND) WHERE id=?",
                trim(error), eventId);
    }

    public boolean begin(String documentId, int version, int maxAttempts) {
        int currentVersion = jdbc.queryForObject("SELECT version_no FROM knowledge_document WHERE id=?", Integer.class, documentId);
        if (currentVersion != version) {
            jdbc.update("UPDATE document_index_task SET status='SUPERSEDED',finished_at=CURRENT_TIMESTAMP,last_error=NULL "
                    + "WHERE document_id=? AND version_no=? AND status NOT IN ('READY','SUPERSEDED')", documentId, version);
            return false;
        }
        return jdbc.update("UPDATE document_index_task SET status='INDEXING',attempt_count=attempt_count+1,started_at=CURRENT_TIMESTAMP,last_error=NULL "
                        + "WHERE document_id=? AND version_no=? AND status IN ('PENDING','DISPATCHED','FAILED') AND attempt_count<?",
                documentId, version, maxAttempts) > 0;
    }

    @Transactional
    public ActivationResult ready(String documentId, int version, int chunks) {
        Integer oldVersion=jdbc.queryForObject("SELECT active_version_no FROM knowledge_document WHERE id=? FOR UPDATE",Integer.class,documentId);
        List<String> oldIds=oldVersion==null||oldVersion==0?List.of():jdbc.query(
                "SELECT id FROM knowledge_chunk WHERE document_id=? AND version_no=?",(rs,n)->rs.getString("id"),documentId,oldVersion);
        int changed=jdbc.update("UPDATE knowledge_document d JOIN knowledge_document_version v ON v.document_id=d.id AND v.version_no=? "
                + "SET d.title=v.title,d.content=v.content,d.status='READY',d.active_version_no=?,d.chunk_count=? "
                + "WHERE d.id=? AND d.version_no=?",version,version,chunks,documentId,version);
        if(changed==0)throw new IllegalStateException("文档版本切换失败："+documentId+" v"+version);
        jdbc.update("UPDATE knowledge_document_version SET status=IF(version_no=?,'ACTIVE','ARCHIVED'),"
                + "chunk_count=IF(version_no=?, ?, chunk_count),activated_at=IF(version_no=?,CURRENT_TIMESTAMP,activated_at) WHERE document_id=?",
                version,version,chunks,version,documentId);
        jdbc.update("UPDATE document_index_task SET status='READY',finished_at=CURRENT_TIMESTAMP,last_error=NULL "
                + "WHERE document_id=? AND version_no=?", documentId, version);
        return new ActivationResult(oldVersion==null?0:oldVersion,oldIds);
    }

    public void deleted(String documentId,int version){
        jdbc.update("DELETE FROM knowledge_chunk WHERE document_id=?",documentId);
        jdbc.update("UPDATE knowledge_document SET status='DELETED',chunk_count=0 WHERE id=? AND version_no=?",documentId,version);
        jdbc.update("UPDATE knowledge_document_source SET parse_status='DELETE_PENDING' "
                + "WHERE document_id=? AND parse_status<>'DELETED'", documentId);
        jdbc.update("UPDATE document_index_task SET status='READY',finished_at=CURRENT_TIMESTAMP,last_error=NULL "
                + "WHERE document_id=? AND version_no=?",documentId,version);
    }

    public void failed(String documentId, int version, String error, int maxAttempts) {
        jdbc.update("UPDATE document_index_task SET status=IF(attempt_count>=?,'DEAD','FAILED'),last_error=?,finished_at=CURRENT_TIMESTAMP "
                + "WHERE document_id=? AND version_no=?", maxAttempts, trim(error), documentId, version);
        String status = jdbc.queryForObject("SELECT status FROM document_index_task WHERE document_id=? AND version_no=?",
                String.class, documentId, version);
        jdbc.update("UPDATE knowledge_document SET status=IF(active_version_no=0,?,'READY') WHERE id=? AND version_no=?",
                "DEAD".equals(status) ? "FAILED" : "PENDING", documentId, version);
    }

    public int replayFailed() {
        Integer taskCount=jdbc.queryForObject("SELECT COUNT(*) FROM document_index_task WHERE status IN ('FAILED','DEAD')",Integer.class);
        jdbc.update("UPDATE document_index_task t JOIN outbox_event e ON e.aggregate_id=t.document_id AND e.aggregate_version=t.version_no "
                + "SET t.status='PENDING',t.attempt_count=0,t.last_error=NULL,t.finished_at=NULL,"
                + "e.status='PENDING',e.attempt_count=0,e.last_error=NULL,e.next_attempt_at=CURRENT_TIMESTAMP "
                + "WHERE t.status IN ('FAILED','DEAD')");
        return taskCount==null?0:taskCount;
    }

    @Transactional
    public boolean requeue(String documentId,int version,String taskType,String eventId,String payload){
        int changed=jdbc.update("UPDATE document_index_task SET status='PENDING',attempt_count=0,last_error=NULL,finished_at=NULL "
                + "WHERE document_id=? AND version_no=? AND task_type=?",documentId,version,taskType);
        if(changed==0)return false;
        jdbc.update("INSERT INTO outbox_event(id,aggregate_id,aggregate_version,event_type,payload_json,status) VALUES(?,?,?,?,?,'PENDING')",
                eventId,documentId,version,"DOCUMENT_RECONCILE_REQUESTED",payload);
        return true;
    }

    public List<TaskView> tasks(int limit) {
        return jdbc.query("SELECT id,document_id,version_no,task_type,status,attempt_count,last_error,created_at,updated_at "
                        + "FROM document_index_task ORDER BY created_at DESC LIMIT ?",
                (rs,n)->new TaskView(rs.getString("id"),rs.getString("document_id"),rs.getInt("version_no"),
                        rs.getString("task_type"),rs.getString("status"),rs.getInt("attempt_count"),
                        rs.getString("last_error"),rs.getObject("created_at",LocalDateTime.class),
                        rs.getObject("updated_at",LocalDateTime.class)), limit);
    }

    public Metrics metrics() {
        int outbox = jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE status='PENDING'", Integer.class);
        int failed = jdbc.queryForObject("SELECT COUNT(*) FROM document_index_task WHERE status IN ('FAILED','DEAD')", Integer.class);
        return new Metrics(outbox, failed);
    }

    private String trim(String error) { String s=error==null?"unknown":error; return s.substring(0,Math.min(1000,s.length())); }
    public record OutboxEvent(String id,String documentId,int version,String type,String payload,int attempts,LocalDateTime createdAt){}
    public record TaskView(String id,String documentId,int version,String type,String status,int attempts,String lastError,LocalDateTime createdAt,LocalDateTime updatedAt){}
    public record Metrics(int pendingOutbox,int failedTasks){}
    public record ActivationResult(int oldVersion,List<String> oldChunkIds){}
}
