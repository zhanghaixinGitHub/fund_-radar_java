package com.fundradar.core.common.api;

import com.fundradar.core.common.trace.TraceContext;

import java.time.Instant;

/**
 * Java 核心服务对浏览器返回的统一响应信封。
 *
 * @param success 请求是否成功完成
 * @param code 业务结果代码
 * @param message 面向调用方的结果说明
 * @param data 实际业务数据；失败时通常为空
 * @param traceId 用于跨 Java 与 Python 服务关联日志的追踪标识
 * @param timestamp 服务端生成响应的时间
 * @param <T> 业务数据类型
 */
public record ApiResponse<T>(
        boolean success,
        String code,
        String message,
        T data,
        String traceId,
        Instant timestamp
) {

    /** 构造成功响应，并自动带入当前请求的追踪标识和时间。 */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, "OK", "success", data, TraceContext.getTraceId(), Instant.now());
    }

    /** 构造不含业务数据的失败响应，供统一异常处理器转换 HTTP 错误。 */
    public static ApiResponse<Void> failure(String code, String message) {
        return new ApiResponse<>(false, code, message, null, TraceContext.getTraceId(), Instant.now());
    }
}
