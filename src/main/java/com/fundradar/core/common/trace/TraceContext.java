package com.fundradar.core.common.trace;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * 当前请求追踪标识的访问工具。
 *
 * Servlet 过滤器将标识写入 MDC；在非 HTTP 上下文中会临时生成 UUID，确保审计与日志仍可关联。
 */
public final class TraceContext {

    public static final String TRACE_ID_KEY = "traceId";

    /** 工具类禁止实例化。 */
    private TraceContext() {
    }

    /** 返回当前 MDC 中的追踪标识；缺失时生成新的 UUID 字符串。 */
    public static String getTraceId() {
        String traceId = MDC.get(TRACE_ID_KEY);
        return traceId == null ? UUID.randomUUID().toString() : traceId;
    }
}
