package com.fundradar.core.integration.ai;

import com.fundradar.core.common.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;

import java.net.URI;

/** M3-G1 特征状态内部读模型客户端；只读取已落库快照，不触发构建或预测。 */
@Service
public class AiFeatureClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiFeatureClient.class);

    private final RestClient restClient;
    private final AiServiceProperties properties;

    public AiFeatureClient(RestClient.Builder restClientBuilder, AiServiceProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getConnectTimeout());
        requestFactory.setReadTimeout(properties.getReadTimeout());
        this.restClient = restClientBuilder.baseUrl(properties.getBaseUrl()).requestFactory(requestFactory).build();
    }

    /** 查询一只基金的最新特征可用状态；读取绝不运行模型。 */
    public AiFeatureStatus getLatestFeatureStatus(String fundCode) {
        try {
            AiFeatureStatus payload = restClient.get()
                    .uri(uriBuilder -> buildFeatureUri(uriBuilder, fundCode))
                    .header("X-Service-Token", properties.getToken())
                    .header("X-Trace-Id", TraceContext.getTraceId())
                    .retrieve()
                    .body(AiFeatureStatus.class);
            if (payload == null) {
                throw new AiServiceUnavailableException("AI service returned an empty feature status", null);
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

    /** 构造特征状态内部接口地址。 */
    private URI buildFeatureUri(UriBuilder uriBuilder, String fundCode) {
        return uriBuilder.path("/internal/v1/features/latest").queryParam("fundCode", fundCode).build();
    }

    /** 记录调用失败原因并转换为统一的 AI 服务不可用异常。 */
    private AiServiceUnavailableException unavailable(Exception exception) {
        LOGGER.error("AiFeatureClient.unavailable   >>> AI feature read-model request failed, baseUrl={}", properties.getBaseUrl(), exception);
        return new AiServiceUnavailableException("AI feature read-model is unavailable", exception);
    }
}
