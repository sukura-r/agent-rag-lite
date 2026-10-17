package com.example.agentrag.common;

public record ApiResponse<T>(boolean success, T data, String message) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data, "ok");
    }
}
