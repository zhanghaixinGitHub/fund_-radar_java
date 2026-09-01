package com.fundradar.core.integration.ai;

import com.fundradar.core.common.trace.TraceContext;
import com.fundradar.core.analysis.service.AnalysisOperationConflictException;
import com.fundradar.core.analysis.service.AnalysisRunNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/** M3-05 管理端到 Python 的受控分析调用；不暴露给浏览器。 */
@Service
public class AiAnalysisClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiAnalysisClient.class);
    private static final String SERVICE_TOKEN_HEADER = "X-Service-Token";
    private static final String TRACE_ID_HEADER = "X-Trace-Id";

    private final RestClient restClient;
    private final AiServiceProperties properties;

    public AiAnalysisClient(RestClient.Builder restClientBuilder, AiServiceProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getConnectTimeout());
        requestFactory.setReadTimeout(properties.getReadTimeout());
        this.restClient = restClientBuilder.baseUrl(properties.getBaseUrl()).requestFactory(requestFactory).build();
    }

    /** 创建持久化回测运行，Python 仅排队，不在本调用中同步执行模型。 */
    public AiAnalysisRunStatus startRollingBacktest(BigDecimal feeRate) {
        try {
            AiAnalysisRunStatus payload = restClient.post()
                    .uri("/internal/v1/analysis/runs/rolling-backtest")
                    .header(SERVICE_TOKEN_HEADER, properties.getToken())
                    .header(TRACE_ID_HEADER, TraceContext.getTraceId())
                    .body(Map.of("fee_rate", feeRate))
                    .retrieve()
                    .body(AiAnalysisRunStatus.class);
            return requirePayload(payload, "AI service returned an empty analysis run");
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 409) {
                throw new AnalysisOperationConflictException("analysis operation was rejected by its current state", exception);
            }
            throw unavailable(exception);
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    /** 查询一条持久分析运行；读取本身不重试或重跑任务。 */
    public AiAnalysisRunStatus getAnalysisRun(UUID analysisRunId) {
        try {
            AiAnalysisRunStatus payload = restClient.get()
                    .uri("/internal/v1/analysis/runs/{analysisRunId}", analysisRunId)
                    .header(SERVICE_TOKEN_HEADER, properties.getToken())
                    .header(TRACE_ID_HEADER, TraceContext.getTraceId())
                    .retrieve()
                    .body(AiAnalysisRunStatus.class);
            return requirePayload(payload, "AI service returned an empty analysis run");
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) {
                throw new AnalysisRunNotFoundException("analysis run is not available", exception);
            }
            if (exception.getStatusCode().value() == 409) {
                throw new AnalysisOperationConflictException("analysis operation was rejected by its current state", exception);
            }
            throw unavailable(exception);
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    /** 由系统管理员显式激活已通过 M3-04 闸门的候选发布。 */
    public AiModelReleaseStatus activateModelRelease(UUID modelReleaseId, String reason) {
        return transitionModelRelease(modelReleaseId, "activate", reason);
    }

    /** 由系统管理员显式暂停发布，保留历史评分与回测结果。 */
    public AiModelReleaseStatus suspendModelRelease(UUID modelReleaseId, String reason) {
        return transitionModelRelease(modelReleaseId, "suspend", reason);
    }

    private AiModelReleaseStatus transitionModelRelease(UUID modelReleaseId, String action, String reason) {
        try {
            AiModelReleaseStatus payload = restClient.post()
                    .uri("/internal/v1/analysis/model-releases/{modelReleaseId}/{action}", modelReleaseId, action)
                    .header(SERVICE_TOKEN_HEADER, properties.getToken())
                    .header(TRACE_ID_HEADER, TraceContext.getTraceId())
                    .body(Map.of("reason", reason))
                    .retrieve()
                    .body(AiModelReleaseStatus.class);
            return requirePayload(payload, "AI service returned an empty model release status");
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 409) {
                throw new AnalysisOperationConflictException("analysis operation was rejected by its current state", exception);
            }
            throw unavailable(exception);
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    private <T> T requirePayload(T payload, String message) {
        if (payload == null) {
            throw new AiServiceUnavailableException(message, null);
        }
        return payload;
    }

    /** 记录调用失败上下文，不打印服务令牌、模型请求体或 Python 错误详情。 */
    private AiServiceUnavailableException unavailable(Exception exception) {
        LOGGER.error("AiAnalysisClient.unavailable   >>> AI analysis request failed, baseUrl={}", properties.getBaseUrl(), exception);
        return new AiServiceUnavailableException("AI analysis service is unavailable", exception);
    }
}
