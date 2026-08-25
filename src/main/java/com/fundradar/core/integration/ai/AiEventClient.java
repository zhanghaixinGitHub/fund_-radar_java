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

/**
 * M2 已审核关联事件内部读模型客户端。
 *
 * 只读取 Python 服务中已审核的事件摘要，绝不向浏览器暴露 Python 地址或原始资讯正文。
 */
@Service
public class AiEventClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiEventClient.class);

    private final RestClient restClient;
    private final AiServiceProperties properties;

    public AiEventClient(RestClient.Builder restClientBuilder, AiServiceProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getConnectTimeout());
        requestFactory.setReadTimeout(properties.getReadTimeout());
        this.restClient = restClientBuilder.baseUrl(properties.getBaseUrl()).requestFactory(requestFactory).build();
    }

    /** 查询一只基金的已审核事件分页数据，不向浏览器暴露 AI 服务。 */
    public AiEventPage listEvents(String fundCode, int pageSize, String cursor) {
        try {
            AiEventPage payload = restClient.get()
                    .uri(uriBuilder -> buildEventsUri(uriBuilder, fundCode, pageSize, cursor))
                    .header("X-Service-Token", properties.getToken())
                    .header("X-Trace-Id", TraceContext.getTraceId())
                    .retrieve()
                    .body(AiEventPage.class);
            if (payload == null) {
                throw new AiServiceUnavailableException("AI service returned an empty event page", null);
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

    /** 构造事件内部接口地址，并仅在游标有效时附加 cursor 参数。 */
    private URI buildEventsUri(UriBuilder uriBuilder, String fundCode, int pageSize, String cursor) {
        uriBuilder.path("/internal/v1/events").queryParam("fundCode", fundCode).queryParam("pageSize", pageSize);
        if (StringUtils.hasText(cursor)) {
            uriBuilder.queryParam("cursor", cursor);
        }
        return uriBuilder.build();
    }

    /** 记录调用失败原因并转换为统一的 AI 服务不可用异常。 */
    private AiServiceUnavailableException unavailable(Exception exception) {
        LOGGER.error("AiEventClient.unavailable   >>> AI event read-model request failed, baseUrl={}", properties.getBaseUrl(), exception);
        return new AiServiceUnavailableException("AI event read-model is unavailable", exception);
    }
}
