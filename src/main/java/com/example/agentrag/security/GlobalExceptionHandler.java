package com.example.agentrag.security;

import com.example.agentrag.common.ApiResponse;
import com.example.agentrag.document.DocumentParsingException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import com.example.agentrag.task.SystemOverloadedException;
import com.example.agentrag.task.SessionBusyException;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private final long maxFileBytes;

    public GlobalExceptionHandler(@Value("${app.document.parsing.max-file-bytes:20971520}") long maxFileBytes) {
        this.maxFileBytes = maxFileBytes;
    }

    @ExceptionHandler(SessionBusyException.class)
    public ResponseEntity<ApiResponse<Void>> sessionBusy(SessionBusyException exception){
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiResponse<>(false,null,exception.getMessage()));
    }

    @ExceptionHandler(SystemOverloadedException.class)
    public ResponseEntity<ApiResponse<Void>> overloaded(SystemOverloadedException exception){
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(new ApiResponse<>(false,null,exception.getMessage()));
    }
    @ExceptionHandler(SecurityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> security(SecurityViolationException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiResponse<>(false, null, exception.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> illegalArgument(IllegalArgumentException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiResponse<>(false, null, exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> validation(MethodArgumentNotValidException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiResponse<>(false, null, "请求参数校验失败"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> uploadTooLarge(MaxUploadSizeExceededException exception) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new ApiResponse<>(false, null, "上传文件超过" + maxFileBytes + "字节限制"));
    }

    @ExceptionHandler(DocumentParsingException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> documentParsing(DocumentParsingException exception) {
        HttpStatus status = switch (exception.errorCode()) {
            case "UNSUPPORTED_TYPE", "TYPE_MISMATCH", "UNSUPPORTED_EMBEDDED_OBJECT" -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            case "TEXT_TOO_LARGE", "TOO_MANY_PAGES", "TOO_MANY_CHUNKS" -> HttpStatus.PAYLOAD_TOO_LARGE;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(new ApiResponse<>(false,
                Map.of("errorCode", exception.errorCode()), exception.getMessage()));
    }
}
