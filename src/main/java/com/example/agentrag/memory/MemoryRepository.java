package com.example.agentrag.memory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

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
        return jdbcTemplate.query("SELECT id,user_id,memory_key,content,embedding_json,status,expires_at,created_at,updated_at "
                        + "FROM user_memory WHERE user_id=? AND status='ACTIVE' "
                        + "AND (expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP) ORDER BY updated_at DESC",
                (rs, rowNum) -> new MemoryRecord(rs.getString("id"), rs.getString("user_id"),
                        rs.getString("memory_key"), rs.getString("content"), rs.getString("embedding_json"),
                        rs.getString("status"), rs.getTimestamp("expires_at") == null ? null
                                : rs.getTimestamp("expires_at").toLocalDateTime(),
                        rs.getTimestamp("created_at").toLocalDateTime(),
                        rs.getTimestamp("updated_at").toLocalDateTime()), userId);
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
}
