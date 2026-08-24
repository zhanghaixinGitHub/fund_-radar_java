package com.fundradar.core.integration.ai;

import com.fundradar.core.common.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;

import java.net.URI;

/** Client for the M0 internal fund read-model contract. */
@Service
public class AiFundClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiFundClient.class);
    private static final String SERVICE_TOKEN_HEADER = "X-Service-Token";
    private static final String TRACE_ID_HEADER = "X-Trace-Id";

    private final RestClient restClient;
    private final AiServiceProperties properties;

    public AiFundClient(RestClient.Builder restClientBuilder, AiServiceProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getConnectTimeout());
        requestFactory.setReadTimeout(properties.getReadTimeout());
        this.restClient = restClientBuilder
                .baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    public AiFundPage listFunds(String keyword, int pageSize, String cursor) {
        try {
            AiFundPage payload = restClient.get()
                    .uri(uriBuilder -> buildFundListUri(uriBuilder, keyword, pageSize, cursor))
                    .header(SERVICE_TOKEN_HEADER, properties.getToken())
                    .header(TRACE_ID_HEADER, TraceContext.getTraceId())
                    .retrieve()
                    .body(AiFundPage.class);
            if (payload == null) {
                throw new AiServiceUnavailableException("AI service returned an empty fund list", null);
            }
            return payload;
        } catch (AiServiceUnavailableException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            throw unavailable(exception);
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    public AiFundDetail getFund(String fundCode) {
        try {
            AiFundDetail payload = restClient.get()
                    .uri("/internal/v1/funds/{fundCode}", fundCode)
                    .header(SERVICE_TOKEN_HEADER, properties.getToken())
                    .header(TRACE_ID_HEADER, TraceContext.getTraceId())
                    .retrieve()
                    .body(AiFundDetail.class);
            if (payload == null) {
                throw new AiServiceUnavailableException("AI service returned an empty fund detail", null);
            }
            return payload;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) {
                throw new FundNotFoundException(fundCode);
            }
            throw unavailable(exception);
        } catch (AiServiceUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    private URI buildFundListUri(UriBuilder uriBuilder, String keyword, int pageSize, String cursor) {
        uriBuilder.path("/internal/v1/funds").queryParam("pageSize", pageSize);
        if (StringUtils.hasText(keyword)) {
            uriBuilder.queryParam("keyword", keyword);
        }
        if (StringUtils.hasText(cursor)) {
            uriBuilder.queryParam("cursor", cursor);
        }
        return uriBuilder.build();
    }

    private AiServiceUnavailableException unavailable(Exception exception) {
        LOGGER.error("AiFundClient.unavailable   >>> AI fund read-model request failed, baseUrl={}", properties.getBaseUrl(), exception);
        return new AiServiceUnavailableException("AI fund read-model is unavailable", exception);
    }
}
