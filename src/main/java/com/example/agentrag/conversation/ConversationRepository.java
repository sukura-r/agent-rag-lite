package com.example.agentrag.conversation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public class ConversationRepository {

    private final JdbcTemplate jdbcTemplate;

    public ConversationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void createSession(String id, String userId, String title) {
        jdbcTemplate.update("INSERT INTO conversation_session(id,user_id,title,status,next_seq) VALUES(?,?,?,'ACTIVE',1)",
                id, userId, title);
    }

    public boolean existsSession(String userId, String id) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM conversation_session WHERE id=? AND user_id=? AND status='ACTIVE'",
                Integer.class, id, userId);
        return count != null && count > 0;
    }

    public int lockAndGetNextSequence(String sessionId) {
        Integer sequence = jdbcTemplate.queryForObject(
                "SELECT next_seq FROM conversation_session WHERE id=? FOR UPDATE", Integer.class, sessionId);
        if (sequence == null) {
            throw new IllegalArgumentException("会话不存在：" + sessionId);
        }
        jdbcTemplate.update("UPDATE conversation_session SET next_seq=next_seq+1 WHERE id=?", sessionId);
        return sequence;
    }

    public void insertMessage(String id, String sessionId, int sequence, String role, String content) {
        jdbcTemplate.update("INSERT INTO conversation_message(id,session_id,seq_no,role,content) VALUES(?,?,?,?,?)",
                id, sessionId, sequence, role, content);
    }

    public List<SessionRecord> findSessions(String userId) {
        return jdbcTemplate.query("SELECT id,title,status,created_at,updated_at FROM conversation_session "
                        + "WHERE user_id=? AND status='ACTIVE' ORDER BY updated_at DESC LIMIT 100",
                (rs, rowNum) -> new SessionRecord(rs.getString("id"), rs.getString("title"),
                        rs.getString("status"), rs.getTimestamp("created_at").toLocalDateTime(),
                        rs.getTimestamp("updated_at").toLocalDateTime()), userId);
    }

    public void touchSession(String sessionId) {
        jdbcTemplate.update("UPDATE conversation_session SET updated_at=CURRENT_TIMESTAMP WHERE id=?", sessionId);
    }

    public void closeSession(String userId, String sessionId) {
        int changed = jdbcTemplate.update("UPDATE conversation_session SET status='CLOSED' WHERE id=? AND user_id=?",
                sessionId, userId);
        if (changed == 0) throw new IllegalArgumentException("会话不存在或无权访问：" + sessionId);
    }

    public List<MessageRecord> findMessages(String sessionId) {
        return jdbcTemplate.query("SELECT id,session_id,seq_no,role,content,created_at "
                        + "FROM conversation_message WHERE session_id=? ORDER BY seq_no",
                (rs, rowNum) -> new MessageRecord(rs.getString("id"), rs.getString("session_id"),
                        rs.getInt("seq_no"), rs.getString("role"), rs.getString("content"),
                        rs.getTimestamp("created_at").toLocalDateTime()), sessionId);
    }

    public List<MessageRecord> findRecentMessages(String sessionId, int limit) {
        return jdbcTemplate.query("SELECT id,session_id,seq_no,role,content,created_at FROM ("
                        + "SELECT id,session_id,seq_no,role,content,created_at FROM conversation_message "
                        + "WHERE session_id=? ORDER BY seq_no DESC LIMIT ?) recent ORDER BY seq_no",
                (rs, rowNum) -> new MessageRecord(rs.getString("id"), rs.getString("session_id"),
                        rs.getInt("seq_no"), rs.getString("role"), rs.getString("content"),
                        rs.getTimestamp("created_at").toLocalDateTime()), sessionId, limit);
    }

    public List<MessageRecord> findMessagesBeforeRecentWindow(String sessionId, int recentLimit) {
        return jdbcTemplate.query("SELECT id,session_id,seq_no,role,content,created_at FROM conversation_message "
                        + "WHERE session_id=? AND seq_no <= GREATEST((SELECT COALESCE(MAX(seq_no),0) FROM conversation_message WHERE session_id=?)-?,0) "
                        + "ORDER BY seq_no",
                (rs, rowNum) -> new MessageRecord(rs.getString("id"), rs.getString("session_id"),
                        rs.getInt("seq_no"), rs.getString("role"), rs.getString("content"),
                        rs.getTimestamp("created_at").toLocalDateTime()), sessionId, sessionId, recentLimit);
    }

    public SummaryRecord findSummary(String userId, String sessionId) {
        List<SummaryRecord> rows = jdbcTemplate.query("SELECT session_id,summarized_until_seq,summary_text,updated_at "
                        + "FROM conversation_summary WHERE session_id=? AND user_id=?",
                (rs, rowNum) -> new SummaryRecord(rs.getString("session_id"),
                        rs.getInt("summarized_until_seq"), rs.getString("summary_text"),
                        rs.getTimestamp("updated_at").toLocalDateTime()), sessionId, userId);
        return rows.stream().findFirst().orElse(null);
    }

    public void upsertSummary(String userId, String sessionId, int untilSeq, String text) {
        jdbcTemplate.update("INSERT INTO conversation_summary(session_id,user_id,summarized_until_seq,summary_text) "
                        + "VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE summarized_until_seq=VALUES(summarized_until_seq),"
                        + "summary_text=VALUES(summary_text),updated_at=CURRENT_TIMESTAMP",
                sessionId, userId, untilSeq, text);
    }

    public record MessageRecord(String id, String sessionId, int sequence, String role,
                                String content, LocalDateTime createdAt) {
    }

    public record SessionRecord(String id, String title, String status,
                                LocalDateTime createdAt, LocalDateTime updatedAt) { }

    public record SummaryRecord(String sessionId, int summarizedUntilSequence,
                                String summaryText, LocalDateTime updatedAt) { }
}
