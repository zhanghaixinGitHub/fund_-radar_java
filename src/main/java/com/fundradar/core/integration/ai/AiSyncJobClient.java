package com.fundradar.core.integration.ai;

import com.fundradar.core.common.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.UUID;

/** Java 访问 Python 同步任务中心的受控内部客户端。 */
@Service
public class AiSyncJobClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiSyncJobClient.class);
    private static final String SERVICE_TOKEN_HEADER = "X-Service-Token";
    private static final String TRACE_ID_HEADER = "X-Trace-Id";

    private final RestClient restClient;
    private final AiServiceProperties properties;

    public AiSyncJobClient(RestClient.Builder restClientBuilder, AiServiceProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getConnectTimeout());
        requestFactory.setReadTimeout(properties.getReadTimeout());
        this.restClient = restClientBuilder
                .baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    /** 创建重点基金日净值增量同步任务，立即返回任务标识和初始状态。 */
    public AiSyncJobStatus startFocusedNavIncremental() {
        try {
            AiSyncJobStatus payload = restClient.post()
                    .uri("/internal/v1/funds/sync-jobs/focused-nav-incremental")
                    .header(SERVICE_TOKEN_HEADER, properties.getToken())
                    .header(TRACE_ID_HEADER, TraceContext.getTraceId())
                    .retrieve()
                    .body(AiSyncJobStatus.class);
            if (payload == null) {
                throw new AiServiceUnavailableException("AI service returned an empty sync job", null);
            }
            return payload;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 409) {
                throw new FocusedNavSyncInProgressException("focused NAV sync is already running", exception);
            }
            throw unavailable(exception);
        } catch (AiServiceUnavailableException | FocusedNavSyncInProgressException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    /** 查询指定同步任务的最新进度；读取本身不会重新触发外部数据请求。 */
    public AiSyncJobStatus getSyncJob(UUID jobId) {
        try {
            AiSyncJobStatus payload = restClient.get()
                    .uri("/internal/v1/funds/sync-jobs/{jobId}", jobId)
                    .header(SERVICE_TOKEN_HEADER, properties.getToken())
                    .header(TRACE_ID_HEADER, TraceContext.getTraceId())
                    .retrieve()
                    .body(AiSyncJobStatus.class);
            if (payload == null) {
                throw new AiServiceUnavailableException("AI service returned an empty sync job", null);
            }
            return payload;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) {
                throw new SyncJobNotFoundException("sync job is not available", exception);
            }
            throw unavailable(exception);
        } catch (AiServiceUnavailableException | SyncJobNotFoundException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    /** 查询 Python 当前进程最近一次任务；首次使用或服务重启后可为空。 */
    public AiSyncJobStatus getLatestFocusedNavIncremental() {
        try {
            return restClient.get()
                    .uri("/internal/v1/funds/sync-jobs/focused-nav-incremental/latest")
                    .header(SERVICE_TOKEN_HEADER, properties.getToken())
                    .header(TRACE_ID_HEADER, TraceContext.getTraceId())
                    .retrieve()
                    .body(AiSyncJobStatus.class);
        } catch (RestClientResponseException exception) {
            throw unavailable(exception);
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    /** 记录脱敏的内部调用上下文，并包装为稳定的服务不可用异常。 */
    private AiServiceUnavailableException unavailable(Exception exception) {
        LOGGER.error("AiSyncJobClient.unavailable   >>> AI sync job request failed, baseUrl={}", properties.getBaseUrl(), exception);
        return new AiServiceUnavailableException("AI sync job service is unavailable", exception);
    }
}
