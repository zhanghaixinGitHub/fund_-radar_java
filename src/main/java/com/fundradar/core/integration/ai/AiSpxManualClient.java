package com.fundradar.core.integration.ai;

import com.fundradar.core.common.trace.TraceContext;
import com.fundradar.core.sync.api.SpxSyncStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/** 固定转发SPX手动采集；沿用服务令牌和TraceID，不执行系统命令、不接受外部指定路径。 */
@Service
public class AiSpxManualClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(AiSpxManualClient.class);
    private final RestClient client;
    private final AiServiceProperties properties;

    public AiSpxManualClient(RestClient.Builder builder, AiServiceProperties properties) {
        this.properties = properties;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeout());
        // 一次Tushare请求可能等待20秒，给来源只读检查和落盘留余量；超时也不重发POST。
        factory.setReadTimeout(Duration.ofSeconds(45));
        client = builder.baseUrl(properties.getBaseUrl()).requestFactory(factory).build();
    }

    public SpxSyncStatus status() { return request(false); }

    public SpxSyncStatus synchronize() { return request(true); }

    private SpxSyncStatus request(boolean synchronize) {
        long started = System.nanoTime();
        try {
            RestClient.RequestHeadersSpec<?> request = synchronize
                    ? client.post().uri("/internal/v1/spx-manual/sync")
                    : client.get().uri("/internal/v1/spx-manual/status");
            SpxSyncStatus result = request.header("X-Service-Token", properties.getToken())
                    .header("X-Trace-Id", TraceContext.getTraceId()).retrieve().body(SpxSyncStatus.class);
            if (result == null || !"MANUAL".equals(result.mode())) {
                throw new IllegalStateException("SPX_RESPONSE_INVALID");
            }
            if (synchronize) {
                LOGGER.info("AiSpxManualClient.request   >>> SPX手动同步返回 traceId={} elapsedMs={} state={}",
                        TraceContext.getTraceId(), (System.nanoTime() - started) / 1_000_000,
                        result.lastAttempt() == null ? "NOT_STARTED" : result.lastAttempt().state());
            }
            return result;
        } catch (RuntimeException error) {
            // 不打印上游异常原文或响应体，防止供应商错误回显配置；保留类型和调用位置。
            LOGGER.error("AiSpxManualClient.request   >>> SPX请求未完成 traceId={} type={} stack={}",
                    TraceContext.getTraceId(), error.getClass().getSimpleName(),
                    java.util.Arrays.toString(error.getStackTrace()));
            throw new AiServiceUnavailableException("SPX手动同步服务暂不可用", null);
        }
    }
}
