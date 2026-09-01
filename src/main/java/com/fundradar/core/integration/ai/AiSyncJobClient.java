package com.fundradar.core.integration.ai;

import com.fundradar.core.common.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Arrays;
import java.util.List;
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

    /** 创建基金市场日净值增量同步任务，立即返回任务标识和初始状态。 */
    public AiSyncJobStatus startMarketNavIncremental() {
        try {
            AiSyncJobStatus payload = restClient.post()
                    .uri("/internal/v1/funds/sync-jobs/market-nav-incremental")
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
                throw new MarketNavSyncInProgressException("market NAV sync is already running", exception);
            }
            throw unavailable(exception);
        } catch (AiServiceUnavailableException | MarketNavSyncInProgressException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    /** 创建基金市场完整资料同步任务，立即返回任务标识和初始状态。 */
    public AiSyncJobStatus startMarketDetails() {
        try {
            AiSyncJobStatus payload = restClient.post()
                    .uri("/internal/v1/funds/sync-jobs/market-details")
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
                throw new MarketNavSyncInProgressException("market sync is already running", exception);
            }
            throw unavailable(exception);
        } catch (AiServiceUnavailableException | MarketNavSyncInProgressException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    /** 创建已落库净值的特征快照任务，不触发任何外部市场数据拉取。 */
    public AiSyncJobStatus startStockFeatureSnapshots() {
        try {
            AiSyncJobStatus payload = restClient.post()
                    .uri("/internal/v1/funds/sync-jobs/stock-feature-snapshots")
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
                throw new MarketNavSyncInProgressException("feature snapshot sync is already running", exception);
            }
            throw unavailable(exception);
        } catch (AiServiceUnavailableException | MarketNavSyncInProgressException exception) {
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
    public AiSyncJobStatus getLatestMarketNavIncremental() {
        try {
            return restClient.get()
                    .uri("/internal/v1/funds/sync-jobs/market-nav-incremental/latest")
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

    /** 查询 Python 当前进程最近一次完整资料同步任务；首次使用或服务重启后可为空。 */
    public AiSyncJobStatus getLatestMarketDetails() {
        try {
            return restClient.get()
                    .uri("/internal/v1/funds/sync-jobs/market-details/latest")
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

    /** 查询 Python 当前进程最近一次特征快照任务；首次使用或服务重启后可为空。 */
    public AiSyncJobStatus getLatestStockFeatureSnapshots() {
        try {
            return restClient.get()
                    .uri("/internal/v1/funds/sync-jobs/stock-feature-snapshots/latest")
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

    /** 查询各同步任务最近一次完整成功时间；读取本身不会触发同步。 */
    public List<AiSyncJobLastSuccess> getLastSuccessfulSyncTimes() {
        try {
            AiSyncJobLastSuccess[] payload = restClient.get()
                    .uri("/internal/v1/funds/sync-jobs/last-success")
                    .header(SERVICE_TOKEN_HEADER, properties.getToken())
                    .header(TRACE_ID_HEADER, TraceContext.getTraceId())
                    .retrieve()
                    .body(AiSyncJobLastSuccess[].class);
            return payload == null ? List.of() : Arrays.asList(payload);
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
