package com.fundradar.core.integration.ai;

import com.fundradar.core.common.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;

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

    private record AiHealthPayload(String service, String status, Instant time) {
    }
}
