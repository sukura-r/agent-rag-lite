CREATE DATABASE IF NOT EXISTS agent_rag DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE agent_rag;

CREATE TABLE IF NOT EXISTS knowledge_document (
    id VARCHAR(32) PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    content MEDIUMTEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    chunk_count INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS knowledge_chunk (
    id VARCHAR(32) PRIMARY KEY,
    document_id VARCHAR(32) NOT NULL,
    chunk_index INT NOT NULL,
    content TEXT NOT NULL,
    char_count INT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_document_chunk(document_id, chunk_index),
    KEY idx_document_id(document_id),
    CONSTRAINT fk_chunk_document FOREIGN KEY(document_id) REFERENCES knowledge_document(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS conversation_session (
    id VARCHAR(32) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL DEFAULT 'local-demo-user',
    title VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    next_seq INT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_session_user_updated(user_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @conversation_user_id_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'conversation_session'
      AND COLUMN_NAME = 'user_id'
);
SET @conversation_user_id_ddl = IF(
    @conversation_user_id_exists = 0,
    'ALTER TABLE conversation_session ADD COLUMN user_id VARCHAR(64) NOT NULL DEFAULT ''local-demo-user'' AFTER id',
    'SELECT 1'
);
PREPARE conversation_user_id_statement FROM @conversation_user_id_ddl;
EXECUTE conversation_user_id_statement;
DEALLOCATE PREPARE conversation_user_id_statement;

CREATE TABLE IF NOT EXISTS conversation_message (
    id VARCHAR(32) PRIMARY KEY,
    session_id VARCHAR(32) NOT NULL,
    seq_no INT NOT NULL,
    role VARCHAR(32) NOT NULL,
    content MEDIUMTEXT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_session_seq(session_id, seq_no),
    KEY idx_message_session(session_id),
    CONSTRAINT fk_message_session FOREIGN KEY(session_id) REFERENCES conversation_session(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS conversation_summary (
    session_id VARCHAR(32) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    summarized_until_seq INT NOT NULL,
    summary_text MEDIUMTEXT NOT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_summary_session FOREIGN KEY(session_id) REFERENCES conversation_session(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS user_memory (
    id VARCHAR(32) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    memory_key VARCHAR(128) NOT NULL,
    content VARCHAR(1000) NOT NULL,
    embedding_json MEDIUMTEXT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    expires_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_memory_key(user_id, memory_key),
    KEY idx_memory_user_status(user_id, status, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS security_audit_log (
    id VARCHAR(32) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    resource_name VARCHAR(128) NOT NULL,
    decision VARCHAR(32) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    details_json TEXT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_security_audit_user_time(user_id, created_at),
    KEY idx_security_audit_event_time(event_type, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @knowledge_version_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_document' AND COLUMN_NAME = 'version_no'
);
SET @knowledge_version_ddl = IF(@knowledge_version_exists = 0,
    'ALTER TABLE knowledge_document ADD COLUMN version_no INT NOT NULL DEFAULT 1 AFTER status', 'SELECT 1');
PREPARE knowledge_version_statement FROM @knowledge_version_ddl;
EXECUTE knowledge_version_statement;
DEALLOCATE PREPARE knowledge_version_statement;

CREATE TABLE IF NOT EXISTS document_index_task (
    id VARCHAR(32) PRIMARY KEY,
    document_id VARCHAR(32) NOT NULL,
    version_no INT NOT NULL,
    task_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    last_error VARCHAR(1000) NULL,
    started_at DATETIME NULL,
    finished_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_document_version_task(document_id, version_no, task_type),
    KEY idx_index_task_status_time(status, updated_at),
    CONSTRAINT fk_index_task_document FOREIGN KEY(document_id) REFERENCES knowledge_document(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS outbox_event (
    id VARCHAR(32) PRIMARY KEY,
    aggregate_id VARCHAR(32) NOT NULL,
    aggregate_version INT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload_json TEXT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_error VARCHAR(1000) NULL,
    sent_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_outbox_status_next(status, next_attempt_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 文档蓝绿版本：active_version_no是当前对外检索版本，version_no是最新提交版本。
SET @active_version_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='knowledge_document' AND COLUMN_NAME='active_version_no');
SET @active_version_ddl = IF(@active_version_exists=0,
    'ALTER TABLE knowledge_document ADD COLUMN active_version_no INT NOT NULL DEFAULT 0 AFTER version_no','SELECT 1');
PREPARE active_version_statement FROM @active_version_ddl;
EXECUTE active_version_statement;
DEALLOCATE PREPARE active_version_statement;

UPDATE knowledge_document SET active_version_no=version_no
WHERE active_version_no=0 AND status IN ('READY','DELETED');

CREATE TABLE IF NOT EXISTS knowledge_document_version (
    document_id VARCHAR(32) NOT NULL,
    version_no INT NOT NULL,
    title VARCHAR(255) NOT NULL,
    content MEDIUMTEXT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'STAGING',
    chunk_count INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    activated_at DATETIME NULL,
    PRIMARY KEY(document_id,version_no),
    KEY idx_document_version_status(document_id,status),
    CONSTRAINT fk_version_document FOREIGN KEY(document_id) REFERENCES knowledge_document(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO knowledge_document_version(document_id,version_no,title,content,status,chunk_count,activated_at)
SELECT id,version_no,title,content,
       IF(status='READY','ACTIVE',IF(status='DELETED','DELETED','STAGING')),
       chunk_count,IF(status='READY',CURRENT_TIMESTAMP,NULL)
FROM knowledge_document;

SET @chunk_version_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='knowledge_chunk' AND COLUMN_NAME='version_no');
SET @chunk_version_ddl = IF(@chunk_version_exists=0,
    'ALTER TABLE knowledge_chunk ADD COLUMN version_no INT NOT NULL DEFAULT 1 AFTER document_id','SELECT 1');
PREPARE chunk_version_statement FROM @chunk_version_ddl;
EXECUTE chunk_version_statement;
DEALLOCATE PREPARE chunk_version_statement;

UPDATE knowledge_chunk c JOIN knowledge_document d ON d.id=c.document_id
SET c.version_no=d.active_version_no WHERE c.version_no=1 AND d.active_version_no<>1;

SET @old_chunk_unique_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='knowledge_chunk' AND INDEX_NAME='uk_document_chunk');
SET @drop_old_chunk_unique = IF(@old_chunk_unique_exists>0,
    'ALTER TABLE knowledge_chunk DROP INDEX uk_document_chunk','SELECT 1');
PREPARE drop_old_chunk_unique_statement FROM @drop_old_chunk_unique;
EXECUTE drop_old_chunk_unique_statement;
DEALLOCATE PREPARE drop_old_chunk_unique_statement;

SET @version_chunk_unique_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='knowledge_chunk' AND INDEX_NAME='uk_document_version_chunk');
SET @add_version_chunk_unique = IF(@version_chunk_unique_exists=0,
    'ALTER TABLE knowledge_chunk ADD UNIQUE KEY uk_document_version_chunk(document_id,version_no,chunk_index)','SELECT 1');
PREPARE add_version_chunk_unique_statement FROM @add_version_chunk_unique;
EXECUTE add_version_chunk_unique_statement;
DEALLOCATE PREPARE add_version_chunk_unique_statement;

CREATE TABLE IF NOT EXISTS agent_task_history (
    task_id VARCHAR(32) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    error_message VARCHAR(1000) NULL,
    next_event_seq INT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_agent_task_user_time(user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @next_event_seq_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='agent_task_history' AND COLUMN_NAME='next_event_seq');
SET @add_next_event_seq = IF(@next_event_seq_exists=0,
    'ALTER TABLE agent_task_history ADD COLUMN next_event_seq INT NOT NULL DEFAULT 1 AFTER error_message','SELECT 1');
PREPARE add_next_event_seq_statement FROM @add_next_event_seq;
EXECUTE add_next_event_seq_statement;
DEALLOCATE PREPARE add_next_event_seq_statement;

CREATE TABLE IF NOT EXISTS agent_task_event (
    task_id VARCHAR(32) NOT NULL,
    event_seq INT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    payload_json MEDIUMTEXT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY(task_id,event_seq),
    KEY idx_agent_event_created(task_id,created_at),
    CONSTRAINT fk_agent_event_task FOREIGN KEY(task_id) REFERENCES agent_task_history(task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

UPDATE agent_task_history h
SET h.next_event_seq=(SELECT COALESCE(MAX(e.event_seq),0)+1
                      FROM agent_task_event e WHERE e.task_id=h.task_id)
WHERE h.next_event_seq<=(SELECT COALESCE(MAX(e.event_seq),0)
                        FROM agent_task_event e WHERE e.task_id=h.task_id);

CREATE TABLE IF NOT EXISTS capacity_dataset_document (
    dataset_key VARCHAR(128) PRIMARY KEY,
    document_id VARCHAR(32) NOT NULL UNIQUE,
    batch_id VARCHAR(32) NOT NULL,
    domain_name VARCHAR(64) NOT NULL,
    generated_chars INT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_capacity_batch(batch_id),
    CONSTRAINT fk_capacity_document FOREIGN KEY(document_id) REFERENCES knowledge_document(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS knowledge_dataset_document (
    dataset_key VARCHAR(128) PRIMARY KEY,
    document_id VARCHAR(32) NOT NULL UNIQUE,
    domain_name VARCHAR(64) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    version_no INT NOT NULL DEFAULT 1,
    public_display BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_dataset_document FOREIGN KEY(document_id) REFERENCES knowledge_document(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS retrieval_evaluation_run (
    id VARCHAR(32) PRIMARY KEY,
    dataset_name VARCHAR(128) NOT NULL,
    query_count INT NOT NULL,
    metrics_json MEDIUMTEXT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS knowledge_document_source (
    document_id VARCHAR(32) NOT NULL,
    version_no INT NOT NULL,
    requested_title VARCHAR(255) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    media_type VARCHAR(128) NOT NULL,
    object_key VARCHAR(700) NOT NULL,
    file_size BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    parse_status VARCHAR(32) NOT NULL DEFAULT 'STORED',
    parser_version VARCHAR(64) NULL,
    error_code VARCHAR(64) NULL,
    last_error VARCHAR(1000) NULL,
    parsed_at DATETIME NULL,
    deleted_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY(document_id,version_no),
    UNIQUE KEY uk_document_source_object(object_key),
    KEY idx_document_source_status(parse_status,updated_at),
    CONSTRAINT fk_document_source_document FOREIGN KEY(document_id) REFERENCES knowledge_document(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @requested_title_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='knowledge_document_source' AND COLUMN_NAME='requested_title');
SET @add_requested_title = IF(@requested_title_exists=0,
    'ALTER TABLE knowledge_document_source ADD COLUMN requested_title VARCHAR(255) NOT NULL DEFAULT '''' AFTER version_no','SELECT 1');
PREPARE add_requested_title_statement FROM @add_requested_title;
EXECUTE add_requested_title_statement;
DEALLOCATE PREPARE add_requested_title_statement;

CREATE TABLE IF NOT EXISTS document_parse_task (
    id VARCHAR(32) PRIMARY KEY,
    document_id VARCHAR(32) NOT NULL,
    version_no INT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_until DATETIME NULL,
    worker_id VARCHAR(128) NULL,
    error_code VARCHAR(64) NULL,
    last_error VARCHAR(1000) NULL,
    started_at DATETIME NULL,
    finished_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_document_parse_version(document_id,version_no),
    KEY idx_document_parse_due(status,next_attempt_at,lease_until),
    CONSTRAINT fk_document_parse_document FOREIGN KEY(document_id) REFERENCES knowledge_document(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
