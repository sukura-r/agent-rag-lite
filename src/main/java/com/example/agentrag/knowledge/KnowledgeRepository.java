package com.example.agentrag.knowledge;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class KnowledgeRepository {

    private final JdbcTemplate jdbcTemplate;

    public KnowledgeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insertDocument(String id, String title, String content) {
        jdbcTemplate.update("INSERT INTO knowledge_document(id,title,content,status,version_no,active_version_no) VALUES(?,?,?,'PENDING',1,0)",
                id, title, content);
    }

    public void insertDocumentVersion(String documentId,int version,String title,String content){
        jdbcTemplate.update("INSERT INTO knowledge_document_version(document_id,version_no,title,content,status) VALUES(?,?,?,?,'STAGING')",
                documentId,version,title,content);
    }

    public void insertChunk(String id, String documentId, int version,int index, String content) {
        jdbcTemplate.update("INSERT INTO knowledge_chunk(id,document_id,version_no,chunk_index,content,char_count) VALUES(?,?,?,?,?,?)",
                id, documentId, version,index, content, content.length());
    }

    public void insertIndexTaskAndOutbox(String taskId,String eventId,String documentId,int version,String payload) {
        insertIndexTaskAndOutbox(taskId, eventId, documentId, version, "UPSERT", payload);
    }

    public void insertIndexTaskAndOutbox(String taskId,String eventId,String documentId,int version,
                                         String taskType,String payload) {
        jdbcTemplate.update("INSERT INTO document_index_task(id,document_id,version_no,task_type,status) VALUES(?,?,?,?, 'PENDING')",
                taskId,documentId,version,taskType);
        jdbcTemplate.update("INSERT INTO outbox_event(id,aggregate_id,aggregate_version,event_type,payload_json,status) VALUES(?,?,?,?,?,'PENDING')",
                eventId,documentId,version,"DOCUMENT_" + taskType + "_REQUESTED",payload);
    }

    public int lockAndGetVersion(String documentId) {
        Integer version = jdbcTemplate.queryForObject(
                "SELECT version_no FROM knowledge_document WHERE id=? AND status NOT IN ('DELETED','DELETING') FOR UPDATE",
                Integer.class, documentId);
        if (version == null) throw new IllegalArgumentException("文档不存在：" + documentId);
        return version;
    }

    public void stageDocumentVersion(String documentId,String title,String content,int version) {
        jdbcTemplate.update("UPDATE knowledge_document SET version_no=? WHERE id=?",version,documentId);
        insertDocumentVersion(documentId,version,title,content);
    }

    public void markDeleting(String documentId,int version) {
        jdbcTemplate.update("UPDATE knowledge_document SET status='DELETING',version_no=? WHERE id=?",version,documentId);
    }

    public void finishDelete(String documentId,int version) {
        jdbcTemplate.update("DELETE FROM knowledge_chunk WHERE document_id=?",documentId);
        jdbcTemplate.update("UPDATE knowledge_document_version SET status='DELETED' WHERE document_id=?",documentId);
        jdbcTemplate.update("UPDATE knowledge_document SET status='DELETED',chunk_count=0 WHERE id=? AND version_no=?",
                documentId,version);
    }

    public List<ChunkRecord> findDocumentChunks(String documentId,int version) {
        return jdbcTemplate.query("SELECT c.id,c.document_id,c.chunk_index,c.content,v.title FROM knowledge_chunk c "
                        + "JOIN knowledge_document_version v ON v.document_id=c.document_id AND v.version_no=c.version_no "
                        + "WHERE c.document_id=? AND c.version_no=? ORDER BY c.chunk_index",
                (rs,n)->new ChunkRecord(rs.getString("id"),rs.getString("document_id"),rs.getInt("chunk_index"),
                        rs.getString("content"),rs.getString("title")),documentId,version);
    }

    public void markReady(String documentId, int chunkCount) {
        jdbcTemplate.update("UPDATE knowledge_document SET status='READY',chunk_count=? WHERE id=?",
                chunkCount, documentId);
    }

    public void markFailed(String documentId) {
        jdbcTemplate.update("UPDATE knowledge_document SET status='FAILED' WHERE id=?", documentId);
    }

    public List<ChunkRecord> findChunks(List<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        return jdbcTemplate.query("SELECT c.id,c.document_id,c.chunk_index,c.content,v.title "
                        + "FROM knowledge_chunk c JOIN knowledge_document d ON d.id=c.document_id "
                        + "JOIN knowledge_document_version v ON v.document_id=c.document_id AND v.version_no=c.version_no "
                        + "WHERE c.id IN (" + placeholders + ") AND d.status='READY' AND c.version_no=d.active_version_no",
                (rs, rowNum) -> new ChunkRecord(rs.getString("id"), rs.getString("document_id"),
                        rs.getInt("chunk_index"), rs.getString("content"), rs.getString("title")),
                ids.toArray());
    }

    public List<ChunkRecord> findAllReadyChunks() {
        return jdbcTemplate.query("SELECT c.id,c.document_id,c.chunk_index,c.content,v.title "
                        + "FROM knowledge_chunk c JOIN knowledge_document d ON d.id=c.document_id "
                        + "JOIN knowledge_document_version v ON v.document_id=c.document_id AND v.version_no=c.version_no "
                        + "WHERE d.status='READY' AND c.version_no=d.active_version_no ORDER BY c.document_id,c.chunk_index",
                (rs, rowNum) -> new ChunkRecord(rs.getString("id"), rs.getString("document_id"),
                        rs.getInt("chunk_index"), rs.getString("content"), rs.getString("title")));
    }

    public List<ChunkRecord> findReadyDocumentChunks(String documentId) {
        return jdbcTemplate.query("SELECT c.id,c.document_id,c.chunk_index,c.content,v.title "
                        + "FROM knowledge_chunk c JOIN knowledge_document d ON d.id=c.document_id "
                        + "JOIN knowledge_document_version v ON v.document_id=c.document_id AND v.version_no=c.version_no "
                        + "WHERE d.id=? AND d.status='READY' AND c.version_no=d.active_version_no ORDER BY c.chunk_index",
                (rs, rowNum) -> new ChunkRecord(rs.getString("id"), rs.getString("document_id"),
                        rs.getInt("chunk_index"), rs.getString("content"), rs.getString("title")),
                documentId);
    }

    public List<DocumentState> findDocumentsForReconciliation(){
        return jdbcTemplate.query("SELECT id,IF(status='DELETED',version_no,active_version_no) version_no,status FROM knowledge_document WHERE status IN ('READY','DELETED')",
                (rs,n)->new DocumentState(rs.getString("id"),rs.getInt("version_no"),rs.getString("status")));
    }

    public Optional<String> findDocumentIdByDatasetKey(String datasetKey) {
        List<String> ids = jdbcTemplate.query("SELECT document_id FROM knowledge_dataset_document WHERE dataset_key=?",
                (rs, rowNum) -> rs.getString("document_id"), datasetKey);
        return ids.stream().findFirst();
    }

    public void insertDatasetMapping(String datasetKey, String documentId, String domain,
                                     String sourceType, int version, boolean publicDisplay) {
        jdbcTemplate.update("INSERT INTO knowledge_dataset_document(dataset_key,document_id,domain_name,source_type,version_no,public_display) "
                        + "VALUES(?,?,?,?,?,?)",
                datasetKey, documentId, domain, sourceType, version, publicDisplay);
    }

    public DatasetStatistics datasetStatistics() {
        return jdbcTemplate.queryForObject("SELECT COUNT(DISTINCT m.document_id) document_count,"
                        + "COALESCE(SUM(CHAR_LENGTH(d.content)),0) total_characters,"
                        + "COALESCE(SUM(d.chunk_count),0) total_chunks,COUNT(DISTINCT m.domain_name) domain_count "
                        + "FROM knowledge_dataset_document m JOIN knowledge_document d ON d.id=m.document_id",
                (rs, rowNum) -> new DatasetStatistics(rs.getInt("document_count"),
                        rs.getLong("total_characters"), rs.getLong("total_chunks"), rs.getInt("domain_count")));
    }

    public List<DomainStatistics> domainStatistics() {
        return jdbcTemplate.query("SELECT m.domain_name,COUNT(*) document_count,"
                        + "COALESCE(SUM(CHAR_LENGTH(d.content)),0) total_characters,COALESCE(SUM(d.chunk_count),0) total_chunks "
                        + "FROM knowledge_dataset_document m JOIN knowledge_document d ON d.id=m.document_id "
                        + "GROUP BY m.domain_name ORDER BY m.domain_name",
                (rs, rowNum) -> new DomainStatistics(rs.getString("domain_name"), rs.getInt("document_count"),
                        rs.getLong("total_characters"), rs.getLong("total_chunks")));
    }

    public void saveEvaluationRun(String id, String datasetName, int queryCount, String metricsJson) {
        jdbcTemplate.update("INSERT INTO retrieval_evaluation_run(id,dataset_name,query_count,metrics_json) VALUES(?,?,?,?)",
                id, datasetName, queryCount, metricsJson);
    }

    public boolean capacityKeyExists(String key){
        Integer count=jdbcTemplate.queryForObject("SELECT COUNT(*) FROM capacity_dataset_document WHERE dataset_key=?",Integer.class,key);
        return count!=null&&count>0;
    }

    public void insertCapacityMapping(String key,String documentId,String batchId,String domain,int chars){
        jdbcTemplate.update("INSERT INTO capacity_dataset_document(dataset_key,document_id,batch_id,domain_name,generated_chars) VALUES(?,?,?,?,?)",
                key,documentId,batchId,domain,chars);
    }

    public CapacityStatistics capacityStatistics(){
        return jdbcTemplate.queryForObject("SELECT COUNT(*) documents,COALESCE(SUM(c.generated_chars),0) characters,"
                        +"COALESCE(SUM(d.chunk_count),0) chunks,COUNT(DISTINCT c.domain_name) domains,"
                        +"SUM(d.status='READY') ready_documents,SUM(d.status<>'READY') pending_documents "
                        +"FROM capacity_dataset_document c JOIN knowledge_document d ON d.id=c.document_id",
                (rs,n)->new CapacityStatistics(rs.getInt("documents"),rs.getLong("characters"),rs.getLong("chunks"),
                        rs.getInt("domains"),rs.getInt("ready_documents"),rs.getInt("pending_documents")));
    }

    public record ChunkRecord(String id, String documentId, int chunkIndex, String content, String title) {
    }

    public record DatasetStatistics(int documentCount, long totalCharacters, long totalChunks, int domainCount) {
    }

    public record DomainStatistics(String domain, int documentCount, long totalCharacters, long totalChunks) {
    }

    public record DocumentState(String id,int version,String status){}
    public record CapacityStatistics(int documents,long characters,long chunks,int domains,
                                     int readyDocuments,int pendingDocuments){}
}
