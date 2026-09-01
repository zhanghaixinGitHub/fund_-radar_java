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
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
    public AiAnalysisRunStatus startRollingBacktest(BigDecimal feeRate, String benchmarkCode) {
        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("fee_rate", feeRate);
            if (benchmarkCode != null) {
                request.put("benchmark_code", benchmarkCode);
            }
            AiAnalysisRunStatus payload = restClient.post()
                    .uri("/internal/v1/analysis/runs/rolling-backtest")
                    .header(SERVICE_TOKEN_HEADER, properties.getToken())
                    .header(TRACE_ID_HEADER, TraceContext.getTraceId())
                    .body(request)
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

    /** 仅排队一只基金的已发布评分解释；Python 决定是否存在合规输入及是否调用 DeepSeek。 */
    public AiAnalysisRunStatus startFundExplanation(String fundCode) {
        try {
            AiAnalysisRunStatus payload = restClient.post()
                    .uri("/internal/v1/analysis/runs/fund-explanations")
                    .header(SERVICE_TOKEN_HEADER, properties.getToken())
                    .header(TRACE_ID_HEADER, TraceContext.getTraceId())
                    .body(Map.of("fund_code", fundCode))
                    .retrieve()
                    .body(AiAnalysisRunStatus.class);
            return requirePayload(payload, "AI service returned an empty explanation run");
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 409) {
                throw new AnalysisOperationConflictException("analysis operation was rejected by its current state", exception);
            }
            throw unavailable(exception);
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    /** 列出本地登记基准的状态和覆盖摘要；不读取日序列。 */
    public List<AiBenchmarkSeriesStatus> listStockBenchmarks() {
        try {
            AiBenchmarkSeriesStatus[] payload = restClient.get()
                    .uri("/internal/v1/analysis/benchmarks")
                    .header(SERVICE_TOKEN_HEADER, properties.getToken())
                    .header(TRACE_ID_HEADER, TraceContext.getTraceId())
                    .retrieve()
                    .body(AiBenchmarkSeriesStatus[].class);
            if (payload == null) {
                throw new AiServiceUnavailableException("AI service returned an empty benchmark list", null);
            }
            return List.of(payload);
        } catch (RestClientResponseException exception) {
            throw unavailable(exception);
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    /** 仅登记候选基准元数据；Python 仍会校验来源存在性，且不会自动启用。 */
    public AiBenchmarkSeriesStatus registerStockBenchmark(
            String benchmarkCode,
            String displayName,
            String sourceCode,
            String licenseReference
    ) {
        return updateBenchmark(
                benchmarkCode,
                Map.of(
                        "display_name", displayName,
                        "source_code", sourceCode,
                        "license_reference", licenseReference
                )
        );
    }

    /** 限量导入人工核验的基准点；Java 不落地或记录具体数值。 */
    public AiBenchmarkSeriesStatus importStockBenchmarkPoints(
            String benchmarkCode,
            List<LocalDate> navDates,
            List<BigDecimal> closingValues,
            List<Instant> sourcePublishedAts
    ) {
        if (navDates.size() != closingValues.size() || navDates.size() != sourcePublishedAts.size()) {
            throw new IllegalArgumentException("benchmark point lists must have identical sizes");
        }
        List<Map<String, Object>> points = new ArrayList<>();
        for (int index = 0; index < navDates.size(); index++) {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("nav_date", navDates.get(index));
            point.put("closing_value", closingValues.get(index));
            if (sourcePublishedAts.get(index) != null) {
                point.put("source_published_at", sourcePublishedAts.get(index));
            }
            points.add(point);
        }
        try {
            AiBenchmarkSeriesStatus payload = restClient.put()
                    .uri("/internal/v1/analysis/benchmarks/{benchmarkCode}/points", benchmarkCode)
                    .header(SERVICE_TOKEN_HEADER, properties.getToken())
                    .header(TRACE_ID_HEADER, TraceContext.getTraceId())
                    .body(Map.of("points", points))
                    .retrieve()
                    .body(AiBenchmarkSeriesStatus.class);
            return requirePayload(payload, "AI service returned an empty benchmark status");
        } catch (RestClientResponseException exception) {
            throw unavailable(exception);
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    /** 显式启用已覆盖且来源已授权的基准；不改变模型发布状态。 */
    public AiBenchmarkSeriesStatus activateStockBenchmark(String benchmarkCode) {
        return transitionBenchmark(benchmarkCode, "activate");
    }

    /** 显式暂停基准，阻止它继续进入新的回测。 */
    public AiBenchmarkSeriesStatus suspendStockBenchmark(String benchmarkCode) {
        return transitionBenchmark(benchmarkCode, "suspend");
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

    private AiBenchmarkSeriesStatus updateBenchmark(String benchmarkCode, Map<String, Object> request) {
        try {
            AiBenchmarkSeriesStatus payload = restClient.put()
                    .uri("/internal/v1/analysis/benchmarks/{benchmarkCode}", benchmarkCode)
                    .header(SERVICE_TOKEN_HEADER, properties.getToken())
                    .header(TRACE_ID_HEADER, TraceContext.getTraceId())
                    .body(request)
                    .retrieve()
                    .body(AiBenchmarkSeriesStatus.class);
            return requirePayload(payload, "AI service returned an empty benchmark status");
        } catch (RestClientResponseException exception) {
            throw unavailable(exception);
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    private AiBenchmarkSeriesStatus transitionBenchmark(String benchmarkCode, String action) {
        try {
            AiBenchmarkSeriesStatus payload = restClient.post()
                    .uri("/internal/v1/analysis/benchmarks/{benchmarkCode}/{action}", benchmarkCode, action)
                    .header(SERVICE_TOKEN_HEADER, properties.getToken())
                    .header(TRACE_ID_HEADER, TraceContext.getTraceId())
                    .retrieve()
                    .body(AiBenchmarkSeriesStatus.class);
            return requirePayload(payload, "AI service returned an empty benchmark status");
        } catch (RestClientResponseException exception) {
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
