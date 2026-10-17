package com.example.agentrag.document;

import org.apache.tika.Tika;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class DocumentUploadService {
    private static final Set<String> DOCX_TYPES = Set.of(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/x-tika-ooxml");
    private static final Set<String> TEXT_TYPES = Set.of("text/plain", "text/markdown", "text/x-web-markdown");
    private final DocumentParsingProperties properties;
    private final DocumentObjectStorage storage;
    private final DocumentIngestionRepository repository;
    private final Tika tika = new Tika();

    public DocumentUploadService(DocumentParsingProperties properties, DocumentObjectStorage storage,
                                 DocumentIngestionRepository repository) {
        this.properties = properties;
        this.storage = storage;
        this.repository = repository;
    }

    public UploadResult uploadNew(MultipartFile file, String requestedTitle) {
        PreparedUpload prepared = prepare(file, requestedTitle);
        try {
            rejectDuplicate(prepared.sha256());
            String documentId = id();
            String objectKey = objectKey(documentId, 1, prepared.sha256(), prepared.filename());
            DocumentIngestionRepository.UploadReservation reservation = repository.reserveNew(documentId,
                    prepared.title(), prepared.filename(), prepared.mediaType(), prepared.size(), prepared.sha256(), objectKey,
                    properties.parserVersion);
            return storeAndFinish(reservation, prepared);
        } finally {
            deleteTemporary(prepared.path());
        }
    }

    public UploadResult uploadVersion(String documentId, MultipartFile file, String requestedTitle) {
        PreparedUpload prepared = prepare(file, requestedTitle);
        try {
            rejectDuplicate(prepared.sha256());
            String updateTitle = requestedTitle == null || requestedTitle.isBlank() ? null : prepared.title();
            DocumentIngestionRepository.UploadReservation reservation = repository.reserveUpdate(documentId,
                    updateTitle, prepared.filename(), prepared.mediaType(), prepared.size(), prepared.sha256(), properties.parserVersion,
                    version -> objectKey(documentId, version, prepared.sha256(), prepared.filename()));
            return storeAndFinish(reservation, prepared);
        } finally {
            deleteTemporary(prepared.path());
        }
    }

    private void rejectDuplicate(String sha256) {
        repository.findExistingUpload(sha256).ifPresent(existing -> {
            throw new IllegalArgumentException("相同文件已上传：" + existing.title() + "（v" + existing.version() + "）");
        });
    }

    private void deleteTemporary(Path path) {
        try { Files.deleteIfExists(path); } catch (Exception ignored) { }
    }

    private UploadResult storeAndFinish(DocumentIngestionRepository.UploadReservation reservation, PreparedUpload prepared) {
        boolean objectStored = false;
        try {
            storage.put(reservation.objectKey(), prepared.path(), prepared.mediaType());
            objectStored = true;
            repository.markStored(reservation);
            return new UploadResult(reservation.documentId(), reservation.version(), reservation.taskId(), "PARSING");
        } catch (RuntimeException exception) {
            boolean cleanupPending = false;
            if (objectStored) {
                try { storage.remove(reservation.objectKey()); }
                catch (RuntimeException cleanupFailure) { cleanupPending = true; }
            }
            repository.markUploadFailed(reservation, exception.getMessage(), cleanupPending);
            throw exception;
        }
    }

    private PreparedUpload prepare(MultipartFile file, String requestedTitle) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("上传文件不能为空");
        if (file.getSize() > properties.maxFileBytes) {
            throw new IllegalArgumentException("文件不能超过" + properties.maxFileBytes + "字节");
        }
        String filename = safeFilename(file.getOriginalFilename());
        String extension = extension(filename);
        if (!Set.of("docx", "pdf", "txt", "md").contains(extension)) {
            throw new DocumentParsingException("UNSUPPORTED_TYPE", "仅支持DOCX、文本型PDF、TXT和Markdown", false);
        }
        Path temporary = null;
        try {
            temporary = Files.createTempFile("zhiheng-upload-", "." + extension);
            try (InputStream input = file.getInputStream()) { Files.copy(input, temporary, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            String mediaType;
            try (InputStream detected = Files.newInputStream(temporary)) {
                mediaType = tika.detect(detected, filename).toLowerCase(Locale.ROOT);
            }
            validateType(extension, mediaType);
            String sha256 = sha256(temporary);
            String title = requestedTitle == null || requestedTitle.isBlank() ? stripExtension(filename) : requestedTitle.strip();
            if (title.isBlank() || title.length() > 255) throw new IllegalArgumentException("文档标题长度必须为1到255个字符");
            return new PreparedUpload(temporary, filename, title, mediaType, Files.size(temporary), sha256);
        } catch (RuntimeException exception) {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (Exception ignored) { }
            throw exception;
        } catch (Exception exception) {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (Exception ignored) { }
            throw new IllegalStateException("读取上传文件失败", exception);
        }
    }

    private void validateType(String extension, String mediaType) {
        boolean valid = switch (extension) {
            case "docx" -> DOCX_TYPES.contains(mediaType);
            case "pdf" -> "application/pdf".equals(mediaType);
            case "txt", "md" -> TEXT_TYPES.contains(mediaType) || mediaType.startsWith("text/");
            default -> false;
        };
        if (!valid) throw new DocumentParsingException("TYPE_MISMATCH",
                "文件扩展名与实际内容不一致：" + extension + " / " + mediaType, false);
    }

    private String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream raw = Files.newInputStream(path); DigestInputStream input = new DigestInputStream(raw, digest)) {
            input.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private String safeFilename(String value) {
        String name = value == null ? "document" : value.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").strip();
        if (name.isBlank()) name = "document";
        if (name.length() > 180) name = name.substring(name.length() - 180);
        return name;
    }

    private String extension(String filename) {
        int index = filename.lastIndexOf('.');
        return index < 0 ? "" : filename.substring(index + 1).toLowerCase(Locale.ROOT);
    }

    private String stripExtension(String filename) {
        int index = filename.lastIndexOf('.');
        return index <= 0 ? filename : filename.substring(0, index);
    }

    private String objectKey(String documentId, int version, String sha256, String filename) {
        return "documents/" + documentId + "/v" + version + "/" + sha256 + "-" + filename.replaceAll("[^\\p{L}\\p{N}._-]", "_");
    }

    private String id() { return UUID.randomUUID().toString().replace("-", ""); }

    private record PreparedUpload(Path path, String filename, String title, String mediaType, long size, String sha256) { }
    public record UploadResult(String documentId, int version, String parseTaskId, String status) { }
}
