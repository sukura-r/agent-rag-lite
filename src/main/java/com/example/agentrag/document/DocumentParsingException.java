package com.example.agentrag.document;

public class DocumentParsingException extends RuntimeException {
    private final String errorCode;
    private final boolean retryable;

    public DocumentParsingException(String errorCode, String message, boolean retryable) {
        super(message);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public DocumentParsingException(String errorCode, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public String errorCode() { return errorCode; }
    public boolean retryable() { return retryable; }
}
