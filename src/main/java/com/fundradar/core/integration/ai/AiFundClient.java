package com.fundradar.core.integration.ai;

import com.fundradar.core.common.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * M0 基金内部读模型客户端。
 *
 * 由 Java 核心服务调用受服务令牌保护的 Python 接口，浏览器不会直接访问该客户端或 Python 服务。
 */
@Service
public class AiFundClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiFundClient.class);
    private static final String SERVICE_TOKEN_HEADER = "X-Service-Token";
    private static final String TRACE_ID_HEADER = "X-Trace-Id";

    private final RestClient restClient;
    private final AiServiceProperties properties;

    public AiFundClient(RestClient.Builder restClientBuilder, AiServiceProperties properties) {
        this.properties = properties;
        this.restClient = restClientBuilder
                .baseUrl(properties.getBaseUrl())
                .requestFactory(createRequestFactory(properties.getReadTimeout()))
                .build();
    }

    /**
     * 查询基金列表内部读模型。
     *
     * Python 返回 404 以外的调用失败统一转换为 AiServiceUnavailableException；空响应也视为不可用。
     */
    public AiFundPage listFunds(String keyword, String fundType, int pageSize, String cursor, Integer page) {
        try {
            AiFundPage payload = restClient.get()
                    .uri(uriBuilder -> buildFundListUri(uriBuilder, keyword, fundType, pageSize, cursor, page))
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

    /** 批量查询指定基金的公开摘要，供 Java 组合当前用户关注页，避免逐行调用内部服务。 */
    public List<AiFundSummary> listFundSummariesByCodes(Collection<String> fundCodes) {
        if (fundCodes.isEmpty()) {
            return List.of();
        }
        try {
            List<AiFundSummary> payload = restClient.get()
                    .uri(uriBuilder -> buildFundBatchUri(uriBuilder, fundCodes))
                    .header(SERVICE_TOKEN_HEADER, properties.getToken())
                    .header(TRACE_ID_HEADER, TraceContext.getTraceId())
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            if (payload == null) {
                throw new AiServiceUnavailableException("AI service returned an empty fund batch", null);
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

    /**
     * 查询单只基金内部详情。
     *
     * @throws FundNotFoundException Python 返回 404 时抛出，供对外异常处理器转换为 404
     * @throws AiServiceUnavailableException Python 服务或网络异常时抛出
     */
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

    /** 查询一只基金在明确日期窗口内的已落库历史净值。 */
    public AiFundNavHistory getFundNavHistory(String fundCode, LocalDate startDate, LocalDate endDate) {
        try {
            AiFundNavHistory payload = restClient.get()
                    .uri(uriBuilder -> buildFundNavHistoryUri(uriBuilder, fundCode, startDate, endDate))
                    .header(SERVICE_TOKEN_HEADER, properties.getToken())
                    .header(TRACE_ID_HEADER, TraceContext.getTraceId())
                    .retrieve()
                    .body(AiFundNavHistory.class);
            if (payload == null) {
                throw new AiServiceUnavailableException("AI service returned an empty NAV history", null);
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

    /** 根据可选关键字、游标或页码构造基金列表内部接口地址。 */
    private URI buildFundListUri(
            UriBuilder uriBuilder, String keyword, String fundType, int pageSize, String cursor, Integer page
    ) {
        uriBuilder.path("/internal/v1/funds").queryParam("pageSize", pageSize);
        if (StringUtils.hasText(keyword)) {
            uriBuilder.queryParam("keyword", keyword);
        }
        if (StringUtils.hasText(fundType)) {
            uriBuilder.queryParam("fundType", fundType);
        }
        if (StringUtils.hasText(cursor)) {
            uriBuilder.queryParam("cursor", cursor);
        }
        if (page != null) {
            uriBuilder.queryParam("page", page);
        }
        return uriBuilder.build();
    }

    /** 构造内部批量基金摘要地址；基金代码作为重复查询参数，不拼接未经编码的字符串。 */
    private URI buildFundBatchUri(UriBuilder uriBuilder, Collection<String> fundCodes) {
        uriBuilder.path("/internal/v1/funds/batch");
        fundCodes.forEach(fundCode -> uriBuilder.queryParam("fundCode", fundCode));
        return uriBuilder.build();
    }

    /** 构造内部历史净值查询地址，日期范围始终由 Java 对外层校验后传入。 */
    private URI buildFundNavHistoryUri(
            UriBuilder uriBuilder, String fundCode, LocalDate startDate, LocalDate endDate
    ) {
        return uriBuilder.path("/internal/v1/funds/{fundCode}/nav-history")
                .queryParam("startDate", startDate)
                .queryParam("endDate", endDate)
                .build(fundCode);
    }

    /** 按本次调用类型构建隔离的 HTTP 超时配置。 */
    private SimpleClientHttpRequestFactory createRequestFactory(Duration readTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getConnectTimeout());
        requestFactory.setReadTimeout(readTimeout);
        return requestFactory;
    }

    /** 记录脱敏的调用上下文，并统一包装为对外可识别的服务不可用异常。 */
    private AiServiceUnavailableException unavailable(Exception exception) {
        LOGGER.error("AiFundClient.unavailable   >>> AI fund read-model request failed, baseUrl={}", properties.getBaseUrl(), exception);
        return new AiServiceUnavailableException("AI fund read-model is unavailable", exception);
    }
}
