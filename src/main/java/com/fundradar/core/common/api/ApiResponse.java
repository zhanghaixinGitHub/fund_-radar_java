package com.fundradar.core.common.api;

import com.fundradar.core.common.trace.TraceContext;

import java.time.Instant;

public record ApiResponse<T>(
        boolean success,
        String code,
        String message,
        T data,
        String traceId,
        Instant timestamp
) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, "OK", "success", data, TraceContext.getTraceId(), Instant.now());
    }

    public static ApiResponse<Void> failure(String code, String message) {
        return new ApiResponse<>(false, code, message, null, TraceContext.getTraceId(), Instant.now());
    }
}
