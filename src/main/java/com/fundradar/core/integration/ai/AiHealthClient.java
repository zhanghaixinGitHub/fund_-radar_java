package com.fundradar.core.integration.ai;

import com.fundradar.core.common.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;

/**
 * Python AI 内部服务健康探测客户端。
 *
 * 探测失败会转为安全的 DOWN 状态，不将连接细节、令牌或异常栈返回给浏览器。
 */
@Service
public class AiHealthClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiHealthClient.class);
    private static final String SERVICE_TOKEN_HEADER = "X-Service-Token";
    private static final String TRACE_ID_HEADER = "X-Trace-Id";

    private final RestClient restClient;
    private final AiServiceProperties properties;

    public AiHealthClient(RestClient.Builder restClientBuilder, AiServiceProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getConnectTimeout());
        requestFactory.setReadTimeout(properties.getReadTimeout());
        this.restClient = restClientBuilder
                .baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    /** 调用受服务令牌保护的 Python 健康接口，并返回可公开展示的 UP 或 DOWN 状态。 */
    public AiHealthStatus checkHealth() {
        Instant checkedAt = Instant.now();
        if (properties.getToken().isBlank()) {
            return new AiHealthStatus("DOWN", "fund-ai", checkedAt, "AI service token is not configured");
        }

        try {
            AiHealthPayload payload = restClient.get()
                    .uri("/internal/v1/health")
                    .header(SERVICE_TOKEN_HEADER, properties.getToken())
                    .header(TRACE_ID_HEADER, TraceContext.getTraceId())
                    .retrieve()
                    .body(AiHealthPayload.class);
            String service = payload == null ? "fund-ai" : payload.service();
            return new AiHealthStatus("UP", service, checkedAt, "reachable");
        } catch (RestClientException exception) {
            LOGGER.warn("AiHealthClient.checkHealth   >>> AI service probe failed, baseUrl={}", properties.getBaseUrl());
            return new AiHealthStatus("DOWN", "fund-ai", checkedAt, "AI service is unreachable");
        }
    }

    /** Python 内部健康接口的最小反序列化结构，仅用于本客户端。 */
    private record AiHealthPayload(String service, String status, Instant time) {
    }
}
