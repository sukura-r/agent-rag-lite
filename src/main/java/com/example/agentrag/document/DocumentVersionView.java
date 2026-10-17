package com.example.agentrag.document;

import org.springframework.jdbc.core.RowMapper;

import java.time.LocalDateTime;

public record DocumentVersionView(int version, String title, String versionStatus, String filename,
                                  String mediaType, Long fileSize, String sha256, String parseStatus,
                                  String parserVersion, String parseTaskStatus, int parseAttempts,
                                  String indexStatus, String errorCode, String lastError,
                                  LocalDateTime createdAt, LocalDateTime parsedAt) {
    static final RowMapper<DocumentVersionView> MAPPER = (rs, n) -> new DocumentVersionView(
            rs.getInt("version_no"), rs.getString("title"), rs.getString("version_status"),
            rs.getString("original_filename"), rs.getString("media_type"),
            rs.getObject("file_size", Long.class), rs.getString("sha256"), rs.getString("parse_status"),
            rs.getString("parser_version"), rs.getString("parse_task_status"), rs.getInt("parse_attempts"),
            rs.getString("index_status"), rs.getString("error_code"), rs.getString("last_error"),
            rs.getObject("created_at", LocalDateTime.class), rs.getObject("parsed_at", LocalDateTime.class));
}
