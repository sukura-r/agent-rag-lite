package com.example.agentrag.document;

import org.springframework.jdbc.core.RowMapper;

import java.time.LocalDateTime;

public record DocumentView(String documentId, String title, String status, int latestVersion, int activeVersion,
                           int chunkCount, String filename, String mediaType, Long fileSize, String sha256,
                           String parseStatus, String parseTaskStatus, String indexStatus,
                           String errorCode, String lastError, LocalDateTime updatedAt) {
    static final RowMapper<DocumentView> MAPPER = (rs, n) -> new DocumentView(
            rs.getString("id"), rs.getString("title"), rs.getString("status"), rs.getInt("version_no"),
            rs.getInt("active_version_no"), rs.getInt("chunk_count"), rs.getString("original_filename"),
            rs.getString("media_type"), rs.getObject("file_size", Long.class), rs.getString("sha256"),
            rs.getString("parse_status"), rs.getString("parse_task_status"), rs.getString("index_status"),
            rs.getString("error_code"), rs.getString("last_error"), rs.getObject("updated_at", LocalDateTime.class));
}
