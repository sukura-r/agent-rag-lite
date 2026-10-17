package com.example.agentrag.document;

import com.example.agentrag.knowledge.KnowledgeRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class DocumentIngestionRepository {
    private final JdbcTemplate jdbc;
    private final KnowledgeRepository knowledge;

    public DocumentIngestionRepository(JdbcTemplate jdbc, KnowledgeRepository knowledge) {
        this.jdbc = jdbc;
        this.knowledge = knowledge;
    }

    public Optional<ExistingUpload> findExistingUpload(String sha256) {
        return jdbc.query("SELECT s.document_id,s.version_no,d.title FROM knowledge_document_source s "
                        + "JOIN knowledge_document d ON d.id=s.document_id "
                        + "WHERE s.sha256=? AND d.status NOT IN ('DELETED','DELETING') "
                        + "AND s.parse_status NOT IN ('DELETED','DELETE_PENDING','UPLOAD_FAILED','SUPERSEDED') "
                        + "AND (d.status<>'READY' OR d.chunk_count>0) "
                        + "ORDER BY s.created_at DESC LIMIT 1",
                (rs, n) -> new ExistingUpload(rs.getString(1), rs.getInt(2), rs.getString(3)), sha256)
                .stream().findFirst();
    }

    @Transactional
    public UploadReservation reserveNew(String documentId, String title, String filename, String mediaType,
                                        long size, String sha256, String objectKey, String parserVersion) {
        jdbc.update("INSERT INTO knowledge_document(id,title,content,status,version_no,active_version_no) "
                        + "VALUES(?,?,?,'PARSING',1,0)", documentId, title, "");
        return insertReservation(documentId, 1, title, filename, mediaType, size, sha256, objectKey, parserVersion);
    }

    @Transactional
    public UploadReservation reserveUpdate(String documentId, String title, String filename, String mediaType,
                                           long size, String sha256, String parserVersion, ObjectKeyFactory objectKeyFactory) {
        List<CurrentDocument> documents = jdbc.query("SELECT version_no,title FROM knowledge_document "
                        + "WHERE id=? AND status<>'DELETED' FOR UPDATE",
                (rs, n) -> new CurrentDocument(rs.getInt(1), rs.getString(2)), documentId);
        if (documents.isEmpty()) throw new IllegalArgumentException("文档不存在：" + documentId);
        CurrentDocument document = documents.get(0);
        int current = document.version();
        int version = current + 1;
        String objectKey = objectKeyFactory.create(version);
        jdbc.update("UPDATE knowledge_document SET version_no=? WHERE id=?", version, documentId);
        String effectiveTitle = title == null || title.isBlank() ? document.title() : title;
        return insertReservation(documentId, version, effectiveTitle, filename, mediaType, size, sha256, objectKey, parserVersion);
    }

    private UploadReservation insertReservation(String documentId, int version, String title, String filename,
                                                String mediaType, long size, String sha256, String objectKey,
                                                String parserVersion) {
        String taskId = id();
        jdbc.update("INSERT INTO knowledge_document_source(document_id,version_no,requested_title,original_filename,media_type,"
                        + "object_key,file_size,sha256,parser_version,parse_status) VALUES(?,?,?,?,?,?,?,?,?,'UPLOADING')",
                documentId, version, title, filename, mediaType, objectKey, size, sha256, parserVersion);
        jdbc.update("INSERT INTO document_parse_task(id,document_id,version_no,status) VALUES(?,?,?,'WAITING_UPLOAD')",
                taskId, documentId, version);
        return new UploadReservation(documentId, version, taskId, objectKey);
    }

    @Transactional
    public void markStored(UploadReservation reservation) {
        jdbc.update("UPDATE knowledge_document_source SET parse_status='STORED',last_error=NULL,error_code=NULL "
                + "WHERE document_id=? AND version_no=?", reservation.documentId(), reservation.version());
        jdbc.update("UPDATE document_parse_task SET status='PENDING',next_attempt_at=CURRENT_TIMESTAMP "
                + "WHERE id=? AND status='WAITING_UPLOAD'", reservation.taskId());
    }

    @Transactional
    public void markUploadFailed(UploadReservation reservation, String error, boolean cleanupPending) {
        jdbc.update("UPDATE knowledge_document_source SET parse_status=?,error_code='STORAGE_ERROR',last_error=? "
                        + "WHERE document_id=? AND version_no=?", cleanupPending ? "DELETE_PENDING" : "UPLOAD_FAILED",
                trim(error), reservation.documentId(), reservation.version());
        jdbc.update("UPDATE document_parse_task SET status='DEAD',error_code='STORAGE_ERROR',last_error=?,finished_at=CURRENT_TIMESTAMP "
                + "WHERE id=?", trim(error), reservation.taskId());
        jdbc.update("UPDATE knowledge_document SET status=IF(active_version_no=0,'FAILED','READY') WHERE id=?",
                reservation.documentId());
    }

    public List<String> dueTaskIds(int limit) {
        return jdbc.query("SELECT id FROM document_parse_task WHERE "
                        + "((status IN ('PENDING','RETRY_WAIT') AND next_attempt_at<=CURRENT_TIMESTAMP) "
                        + "OR (status='PARSING' AND lease_until<CURRENT_TIMESTAMP)) ORDER BY created_at LIMIT ?",
                (rs, n) -> rs.getString(1), limit);
    }

    public boolean claim(String taskId, String workerId, int leaseSeconds) {
        return jdbc.update("UPDATE document_parse_task SET status='PARSING',attempt_count=attempt_count+1,worker_id=?,"
                        + "lease_until=?,started_at=CURRENT_TIMESTAMP,last_error=NULL,error_code=NULL "
                        + "WHERE id=? AND ((status IN ('PENDING','RETRY_WAIT') AND next_attempt_at<=CURRENT_TIMESTAMP) "
                        + "OR (status='PARSING' AND lease_until<CURRENT_TIMESTAMP))",
                workerId, LocalDateTime.now().plusSeconds(leaseSeconds), taskId) > 0;
    }

    public ParseWork loadWork(String taskId) {
        List<ParseWork> values = jdbc.query("SELECT t.id,t.document_id,t.version_no,t.attempt_count,s.requested_title,"
                        + "s.original_filename,s.media_type,s.object_key FROM document_parse_task t "
                        + "JOIN knowledge_document_source s ON s.document_id=t.document_id AND s.version_no=t.version_no WHERE t.id=?",
                (rs, n) -> new ParseWork(rs.getString("id"), rs.getString("document_id"), rs.getInt("version_no"),
                        rs.getInt("attempt_count"), rs.getString("requested_title"), rs.getString("original_filename"),
                        rs.getString("media_type"), rs.getString("object_key")), taskId);
        if (values.isEmpty()) throw new IllegalArgumentException("解析任务不存在：" + taskId);
        return values.get(0);
    }

    @Transactional
    public boolean complete(ParseWork work, String content, List<String> chunks, String parserVersion) {
        Integer latest = jdbc.queryForObject("SELECT version_no FROM knowledge_document WHERE id=? FOR UPDATE",
                Integer.class, work.documentId());
        if (latest == null || latest != work.version()) {
            markSuperseded(work.taskId(), work.documentId(), work.version());
            return false;
        }
        knowledge.insertDocumentVersion(work.documentId(), work.version(), work.title(), content);
        for (int i = 0; i < chunks.size(); i++) {
            knowledge.insertChunk(DocumentIds.chunkId(work.documentId(), work.version(), i), work.documentId(), work.version(), i, chunks.get(i));
        }
        String indexTaskId = id(), eventId = id();
        String payload = "{\"eventId\":\"" + eventId + "\",\"documentId\":\"" + work.documentId()
                + "\",\"version\":" + work.version() + ",\"taskType\":\"UPSERT\"}";
        knowledge.insertIndexTaskAndOutbox(indexTaskId, eventId, work.documentId(), work.version(), payload);
        jdbc.update("UPDATE knowledge_document_source SET parse_status='PARSED',parser_version=?,parsed_at=CURRENT_TIMESTAMP,"
                        + "error_code=NULL,last_error=NULL WHERE document_id=? AND version_no=?",
                parserVersion, work.documentId(), work.version());
        jdbc.update("UPDATE document_parse_task SET status='SUCCEEDED',lease_until=NULL,finished_at=CURRENT_TIMESTAMP,"
                + "error_code=NULL,last_error=NULL WHERE id=?", work.taskId());
        jdbc.update("UPDATE knowledge_document SET status=IF(active_version_no=0,'PENDING','READY') WHERE id=?", work.documentId());
        return true;
    }

    @Transactional
    public void retryLater(ParseWork work, String code, String error, LocalDateTime nextAttempt) {
        jdbc.update("UPDATE document_parse_task SET status='RETRY_WAIT',next_attempt_at=?,lease_until=NULL,error_code=?,last_error=? "
                + "WHERE id=?", nextAttempt, code, trim(error), work.taskId());
        jdbc.update("UPDATE knowledge_document_source SET parse_status='RETRY_WAIT',error_code=?,last_error=? "
                + "WHERE document_id=? AND version_no=?", code, trim(error), work.documentId(), work.version());
    }

    @Transactional
    public void failPermanently(ParseWork work, String code, String error) {
        String sourceStatus = "OCR_REQUIRED".equals(code) ? "OCR_REQUIRED"
                : ("ENCRYPTED_DOCUMENT".equals(code) || code.startsWith("UNSUPPORTED")) ? "UNSUPPORTED" : "FAILED";
        jdbc.update("UPDATE document_parse_task SET status='DEAD',lease_until=NULL,error_code=?,last_error=?,finished_at=CURRENT_TIMESTAMP "
                + "WHERE id=?", code, trim(error), work.taskId());
        jdbc.update("UPDATE knowledge_document_source SET parse_status=?,error_code=?,last_error=? WHERE document_id=? AND version_no=?",
                sourceStatus, code, trim(error), work.documentId(), work.version());
        jdbc.update("UPDATE knowledge_document SET status=IF(active_version_no=0,'FAILED','READY') WHERE id=?", work.documentId());
    }

    private void markSuperseded(String taskId, String documentId, int version) {
        jdbc.update("UPDATE document_parse_task SET status='SUPERSEDED',lease_until=NULL,finished_at=CURRENT_TIMESTAMP WHERE id=?", taskId);
        jdbc.update("UPDATE knowledge_document_source SET parse_status='SUPERSEDED' WHERE document_id=? AND version_no=?", documentId, version);
    }

    @Transactional
    public boolean retry(String documentId, int version) {
        int changed = jdbc.update("UPDATE document_parse_task SET status='PENDING',attempt_count=0,next_attempt_at=CURRENT_TIMESTAMP,"
                        + "lease_until=NULL,error_code=NULL,last_error=NULL,finished_at=NULL WHERE document_id=? AND version_no=? "
                        + "AND status IN ('DEAD','RETRY_WAIT') AND EXISTS (SELECT 1 FROM knowledge_document_source s "
                        + "WHERE s.document_id=document_parse_task.document_id AND s.version_no=document_parse_task.version_no "
                        + "AND s.parse_status IN ('FAILED','RETRY_WAIT'))",
                documentId, version);
        if (changed > 0) jdbc.update("UPDATE knowledge_document_source SET parse_status='STORED',error_code=NULL,last_error=NULL "
                + "WHERE document_id=? AND version_no=?", documentId, version);
        return changed > 0;
    }

    public List<DocumentView> list(int limit, int offset) {
        return jdbc.query("SELECT d.id,COALESCE(s.requested_title,d.title) title,d.status,d.version_no,d.active_version_no,d.chunk_count,d.updated_at,"
                        + "s.original_filename,s.media_type,s.file_size,s.sha256,s.parse_status,s.error_code,s.last_error,"
                        + "COALESCE(t.status,'NONE') parse_task_status,COALESCE(i.status,'NONE') index_status "
                        + "FROM knowledge_document d LEFT JOIN knowledge_document_source s ON s.document_id=d.id AND s.version_no=d.version_no "
                        + "LEFT JOIN document_parse_task t ON t.document_id=d.id AND t.version_no=d.version_no "
                        + "LEFT JOIN document_index_task i ON i.document_id=d.id AND i.version_no=d.version_no AND i.task_type='UPSERT' "
                        + "WHERE d.status<>'DELETED' ORDER BY d.updated_at DESC,d.id DESC LIMIT ? OFFSET ?", DocumentView.MAPPER, limit, offset);
    }

    public int countVisible() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_document WHERE status<>'DELETED'", Integer.class);
        return count == null ? 0 : count;
    }

    public DocumentView find(String documentId) {
        List<DocumentView> values = jdbc.query("SELECT d.id,COALESCE(s.requested_title,d.title) title,d.status,d.version_no,d.active_version_no,d.chunk_count,d.updated_at,"
                        + "s.original_filename,s.media_type,s.file_size,s.sha256,s.parse_status,s.error_code,s.last_error,"
                        + "COALESCE(t.status,'NONE') parse_task_status,COALESCE(i.status,'NONE') index_status "
                        + "FROM knowledge_document d LEFT JOIN knowledge_document_source s ON s.document_id=d.id AND s.version_no=d.version_no "
                        + "LEFT JOIN document_parse_task t ON t.document_id=d.id AND t.version_no=d.version_no "
                        + "LEFT JOIN document_index_task i ON i.document_id=d.id AND i.version_no=d.version_no AND i.task_type='UPSERT' WHERE d.id=?",
                DocumentView.MAPPER, documentId);
        if (values.isEmpty()) throw new IllegalArgumentException("文档不存在：" + documentId);
        return values.get(0);
    }

    public List<DocumentVersionView> versions(String documentId) {
        return jdbc.query("SELECT v.version_no,v.title,v.status version_status,s.original_filename,s.media_type,s.file_size,"
                        + "s.sha256,s.parse_status,s.parser_version,s.error_code,s.last_error,s.created_at,s.parsed_at,"
                        + "COALESCE(t.status,'NONE') parse_task_status,COALESCE(t.attempt_count,0) parse_attempts,"
                        + "COALESCE(i.status,'NONE') index_status FROM knowledge_document_version v "
                        + "LEFT JOIN knowledge_document_source s ON s.document_id=v.document_id AND s.version_no=v.version_no "
                        + "LEFT JOIN document_parse_task t ON t.document_id=v.document_id AND t.version_no=v.version_no "
                        + "LEFT JOIN document_index_task i ON i.document_id=v.document_id AND i.version_no=v.version_no AND i.task_type='UPSERT' "
                        + "WHERE v.document_id=? UNION ALL "
                        + "SELECT s.version_no,s.requested_title,'NOT_CREATED',s.original_filename,s.media_type,s.file_size,s.sha256,"
                        + "s.parse_status,s.parser_version,s.error_code,s.last_error,s.created_at,s.parsed_at,"
                        + "COALESCE(t.status,'NONE'),COALESCE(t.attempt_count,0),'NONE' FROM knowledge_document_source s "
                        + "LEFT JOIN document_parse_task t ON t.document_id=s.document_id AND t.version_no=s.version_no "
                        + "WHERE s.document_id=? AND NOT EXISTS (SELECT 1 FROM knowledge_document_version v "
                        + "WHERE v.document_id=s.document_id AND v.version_no=s.version_no) ORDER BY version_no DESC",
                DocumentVersionView.MAPPER, documentId, documentId);
    }

    public SourceFile source(String documentId, int version) {
        List<SourceFile> values = jdbc.query("SELECT original_filename,media_type,object_key,file_size FROM knowledge_document_source "
                        + "WHERE document_id=? AND version_no=? AND parse_status<>'DELETED'",
                (rs, n) -> new SourceFile(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4)), documentId, version);
        if (values.isEmpty()) throw new IllegalArgumentException("文档原件不存在");
        return values.get(0);
    }

    public List<SourceToDelete> pendingDeletes(int limit) {
        return jdbc.query("SELECT document_id,version_no,object_key FROM knowledge_document_source WHERE parse_status='DELETE_PENDING' LIMIT ?",
                (rs, n) -> new SourceToDelete(rs.getString(1), rs.getInt(2), rs.getString(3)), limit);
    }

    public void markDeleted(SourceToDelete source) {
        jdbc.update("UPDATE knowledge_document_source SET parse_status='DELETED',deleted_at=CURRENT_TIMESTAMP,last_error=NULL "
                + "WHERE document_id=? AND version_no=?", source.documentId(), source.version());
    }

    public void markDeleteFailed(SourceToDelete source, String error) {
        jdbc.update("UPDATE knowledge_document_source SET last_error=? WHERE document_id=? AND version_no=?",
                trim(error), source.documentId(), source.version());
    }

    private String trim(String error) {
        String value = error == null ? "unknown" : error;
        return value.substring(0, Math.min(1000, value.length()));
    }

    private String id() { return UUID.randomUUID().toString().replace("-", ""); }

    public interface ObjectKeyFactory { String create(int version); }
    public record ExistingUpload(String documentId, int version, String title) { }
    private record CurrentDocument(int version, String title) { }
    public record UploadReservation(String documentId, int version, String taskId, String objectKey) { }
    public record ParseWork(String taskId, String documentId, int version, int attempts, String title,
                            String filename, String mediaType, String objectKey) { }
    public record SourceFile(String filename, String mediaType, String objectKey, long size) { }
    public record SourceToDelete(String documentId, int version, String objectKey) { }
}
