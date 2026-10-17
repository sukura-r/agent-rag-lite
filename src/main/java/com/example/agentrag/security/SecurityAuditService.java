package com.example.agentrag.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class SecurityAuditService {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final PromptSecurityService promptSecurity;

    public SecurityAuditService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                                PromptSecurityService promptSecurity) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.promptSecurity = promptSecurity;
    }

    public void record(String userId, String eventType, String resource, String decision,
                       String reason, Map<String, Object> details) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO security_audit_log
                    (id, user_id, event_type, resource_name, decision, reason, details_json)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, id(), safe(userId, 64), safe(eventType, 64), safe(resource, 128),
                    safe(decision, 32), safe(reason, 500), promptSecurity.redact(
                            objectMapper.writeValueAsString(details == null ? Map.of() : details)));
        } catch (RuntimeException | JsonProcessingException ignored) {
            // 安全审计失败不能覆盖原始业务拒绝；生产环境应同时发送监控告警。
        }
    }

    public List<AuditRecord> recent(int limit) {
        int safeLimit = Math.max(1, Math.min(100, limit));
        return jdbcTemplate.query("""
                SELECT id, user_id, event_type, resource_name, decision, reason, details_json, created_at
                FROM security_audit_log ORDER BY created_at DESC LIMIT ?
                """, (rs, rowNum) -> new AuditRecord(rs.getString("id"), rs.getString("user_id"),
                rs.getString("event_type"), rs.getString("resource_name"), rs.getString("decision"),
                rs.getString("reason"), rs.getString("details_json"),
                rs.getObject("created_at", LocalDateTime.class)), safeLimit);
    }

    private String safe(String value, int max) {
        String result = value == null ? "" : value;
        return result.substring(0, Math.min(max, result.length()));
    }

    private String id() { return UUID.randomUUID().toString().replace("-", ""); }

    public record AuditRecord(String id, String userId, String eventType, String resource,
                              String decision, String reason, String detailsJson, LocalDateTime createdAt) { }
}
