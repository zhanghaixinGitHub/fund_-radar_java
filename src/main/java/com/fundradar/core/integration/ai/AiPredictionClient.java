package com.fundradar.core.integration.ai;

import com.fundradar.core.common.trace.TraceContext;
import com.fundradar.core.watchlist.api.WatchlistPredictionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/** 服务Token只在Java到Python使用；查询不会触发同步、保存、训练或发布。 */
@Service
public class AiPredictionClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(AiPredictionClient.class);
    private final RestClient client;
    private final AiServiceProperties properties;

    public AiPredictionClient(RestClient.Builder builder, AiServiceProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeout());
        factory.setReadTimeout(properties.getReadTimeout());
        this.client = builder.baseUrl(properties.getBaseUrl()).requestFactory(factory).build();
    }

    /** 有界单次内部读取；异常不向浏览器透传URL、Token、SQL或内部响应。 */
    public WatchlistPredictionResponse read(String fundCode) {
        long started = System.nanoTime();
        String traceId = TraceContext.getTraceId();
        try {
            AiWatchlistPrediction payload = client.get()
                    .uri("/internal/v1/predictions/{fundCode}", fundCode)
                    .header("X-Service-Token", properties.getToken())
                    .header("X-Trace-Id", traceId)
                    .retrieve().body(AiWatchlistPrediction.class);
            WatchlistPredictionResponse result = WatchlistPredictionResponse.from(payload, fundCode);
            LOGGER.info("AiPredictionClient.read   >>>   traceId={}, fundCode={}, status={}, elapsedMs={}",
                    traceId, fundCode, result.status(), (System.nanoTime() - started) / 1_000_000);
            return result;
        } catch (RuntimeException error) {
            // 异常文本可能含内部URL或响应体，仅记录异常类型及当前栈位置，不记录敏感原文。
            LOGGER.warn("AiPredictionClient.read   >>>   unavailable, traceId={}, fundCode={}, errorType={}, elapsedMs={}",
                    traceId, fundCode, error.getClass().getSimpleName(), (System.nanoTime() - started) / 1_000_000);
            LOGGER.debug("AiPredictionClient.read   >>>   failure frames={}", java.util.Arrays.toString(error.getStackTrace()));
            return WatchlistPredictionResponse.unavailable(fundCode);
        }
    }
}
