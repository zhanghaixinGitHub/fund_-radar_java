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
 * M3 评分结果内部读模型客户端。
 *
 * 只查询已落库的评分结果，不会通过读取接口触发模型运算或生成预测。
 */
@Service
public class AiSignalClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiSignalClient.class);

    private final RestClient restClient;
    private final AiServiceProperties properties;

    public AiSignalClient(RestClient.Builder restClientBuilder, AiServiceProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getConnectTimeout());
        requestFactory.setReadTimeout(properties.getReadTimeout());
        this.restClient = restClientBuilder.baseUrl(properties.getBaseUrl()).requestFactory(requestFactory).build();
    }

    /** 查询一只基金已持久化的评分结果分页；该方法绝不启动模型执行。 */
    public AiSignalPage listSignals(String fundCode, int pageSize, String cursor) {
        try {
            AiSignalPage payload = restClient.get()
                    .uri(uriBuilder -> buildSignalsUri(uriBuilder, fundCode, pageSize, cursor))
                    .header("X-Service-Token", properties.getToken())
                    .header("X-Trace-Id", TraceContext.getTraceId())
                    .retrieve()
                    .body(AiSignalPage.class);
            if (payload == null) {
                throw new AiServiceUnavailableException("AI service returned an empty signal page", null);
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

    /** 构造评分结果内部接口地址，并仅在游标有效时附加 cursor 参数。 */
    private URI buildSignalsUri(UriBuilder uriBuilder, String fundCode, int pageSize, String cursor) {
        uriBuilder.path("/internal/v1/signals").queryParam("fundCode", fundCode).queryParam("pageSize", pageSize);
        if (StringUtils.hasText(cursor)) {
            uriBuilder.queryParam("cursor", cursor);
        }
        return uriBuilder.build();
    }

    /** 记录调用失败原因并转换为统一的 AI 服务不可用异常。 */
    private AiServiceUnavailableException unavailable(Exception exception) {
        LOGGER.error("AiSignalClient.unavailable   >>> AI signal read-model request failed, baseUrl={}", properties.getBaseUrl(), exception);
        return new AiServiceUnavailableException("AI signal read-model is unavailable", exception);
    }
}
