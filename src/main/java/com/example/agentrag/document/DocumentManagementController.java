package com.example.agentrag.document;

import com.example.agentrag.common.ApiResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/knowledge/documents")
public class DocumentManagementController {
    private final DocumentUploadService uploads;
    private final DocumentManagementService management;

    public DocumentManagementController(DocumentUploadService uploads, DocumentManagementService management) {
        this.uploads = uploads;
        this.management = management;
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<DocumentUploadService.UploadResult> upload(@RequestParam("file") MultipartFile file,
                                                                  @RequestParam(required = false) String title) {
        return ApiResponse.success(uploads.uploadNew(file, title));
    }

    @PutMapping(value = "/{documentId}/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<DocumentUploadService.UploadResult> update(@PathVariable String documentId,
                                                                  @RequestParam("file") MultipartFile file,
                                                                  @RequestParam(required = false) String title) {
        return ApiResponse.success(uploads.uploadVersion(documentId, file, title));
    }

    @GetMapping
    public ApiResponse<List<DocumentView>> list(@RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(management.list(page, size));
    }

    @GetMapping("/count")
    public ApiResponse<Integer> count() {
        return ApiResponse.success(management.count());
    }

    @GetMapping("/{documentId}")
    public ApiResponse<DocumentManagementService.DocumentDetail> find(@PathVariable String documentId) {
        return ApiResponse.success(management.find(documentId));
    }

    @PostMapping("/{documentId}/versions/{version}/parse/retry")
    public ApiResponse<String> retry(@PathVariable String documentId, @PathVariable int version) {
        return ApiResponse.success(management.retry(documentId, version));
    }

    @GetMapping("/{documentId}/versions/{version}/source")
    public ResponseEntity<byte[]> source(@PathVariable String documentId, @PathVariable int version) {
        var download = management.download(documentId, version);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(download.mediaType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(download.filename(), StandardCharsets.UTF_8).build().toString())
                .body(download.content());
    }
}
