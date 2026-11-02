package com.example.agentrag.memory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public class MemoryRepository {
    private final JdbcTemplate jdbcTemplate;

    public MemoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void upsert(String id, String userId, String key, String content, String embeddingJson,
                       LocalDateTime expiresAt) {
        jdbcTemplate.update("INSERT INTO user_memory(id,user_id,memory_key,content,embedding_json,status,expires_at) "
                        + "VALUES(?,?,?,?,?,'ACTIVE',?) ON DUPLICATE KEY UPDATE content=VALUES(content),"
                        + "embedding_json=VALUES(embedding_json),status='ACTIVE',expires_at=VALUES(expires_at),"
                        + "updated_at=CURRENT_TIMESTAMP",
                id, userId, key, content, embeddingJson, expiresAt);
    }

    public List<MemoryRecord> active(String userId) {
        return candidates(userId, Integer.MAX_VALUE);
    }

    public List<MemoryRecord> candidates(String userId, int limit) {
        return jdbcTemplate.query("SELECT id,user_id,memory_key,content,embedding_json,status,expires_at,created_at,updated_at "
                        + "FROM user_memory WHERE user_id=? AND status='ACTIVE' "
                        + "AND (expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP) ORDER BY updated_at DESC,id LIMIT ?",
                (rs, rowNum) -> new MemoryRecord(rs.getString("id"), rs.getString("user_id"),
                        rs.getString("memory_key"), rs.getString("content"), rs.getString("embedding_json"),
                        rs.getString("status"), rs.getTimestamp("expires_at") == null ? null
                                : rs.getTimestamp("expires_at").toLocalDateTime(),
                        rs.getTimestamp("created_at").toLocalDateTime(),
                        rs.getTimestamp("updated_at").toLocalDateTime()), userId, limit);
    }

    @Transactional
    public void upsertWithEvidence(String id, String userId, String key, String content, String embeddingJson,
                                   LocalDateTime expiresAt, String taskId, String sessionId, String evidence, String origin) {
        upsert(id, userId, key, content, embeddingJson, expiresAt);
        jdbcTemplate.update("INSERT INTO user_memory_evidence(memory_id,source_task_id,session_id,source_text,origin) "
                        + "SELECT id,?,?,?,? FROM user_memory WHERE user_id=? AND memory_key=? "
                        + "ON DUPLICATE KEY UPDATE source_task_id=VALUES(source_task_id),session_id=VALUES(session_id),"
                        + "source_text=VALUES(source_text),origin=VALUES(origin),updated_at=CURRENT_TIMESTAMP",
                taskId, sessionId, evidence, origin, userId, key);
    }

    public java.util.Optional<MemoryEvidence> evidence(String userId, String memoryId) {
        return jdbcTemplate.query("SELECT e.source_task_id,e.session_id,e.source_text,e.origin,e.updated_at "
                        + "FROM user_memory_evidence e JOIN user_memory m ON m.id=e.memory_id "
                        + "WHERE m.user_id=? AND m.id=? AND m.status='ACTIVE' "
                        + "AND (m.expires_at IS NULL OR m.expires_at>CURRENT_TIMESTAMP)",
                (rs, n) -> new MemoryEvidence(rs.getString("source_task_id"), rs.getString("session_id"),
                        rs.getString("source_text"), rs.getString("origin"), rs.getTimestamp("updated_at").toLocalDateTime()),
                userId, memoryId).stream().findFirst();
    }

    public int deactivateByKey(String userId, String key) {
        return jdbcTemplate.update("UPDATE user_memory SET status='DELETED',updated_at=CURRENT_TIMESTAMP "
                + "WHERE user_id=? AND memory_key=? AND status='ACTIVE'", userId, key);
    }

    public int deactivateById(String userId, String id) {
        return jdbcTemplate.update("UPDATE user_memory SET status='DELETED',updated_at=CURRENT_TIMESTAMP "
                + "WHERE user_id=? AND id=? AND status='ACTIVE'", userId, id);
    }

    public record MemoryRecord(String id, String userId, String key, String content,
                               String embeddingJson, String status, LocalDateTime expiresAt,
                               LocalDateTime createdAt, LocalDateTime updatedAt) { }
    public record MemoryEvidence(String taskId, String sessionId, String sourceText, String origin, LocalDateTime updatedAt) { }
}
