package com.fundradar.core.sync.service;

import com.fundradar.core.integration.ai.AiSyncJobClient;
import com.fundradar.core.integration.ai.AiSyncJobLastSuccess;
import com.fundradar.core.integration.ai.AiSyncJobStatus;
import com.fundradar.core.sync.api.SyncJobLastSuccessResponse;
import com.fundradar.core.sync.api.SyncJobResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/** 将 Java 对外同步中心请求受控转发到 Python 本机后台任务。 */
@Service
public class InternalSyncJobService implements SyncJobService {

    private static final Logger LOGGER = LoggerFactory.getLogger(InternalSyncJobService.class);

    private final AiSyncJobClient aiSyncJobClient;

    public InternalSyncJobService(AiSyncJobClient aiSyncJobClient) {
        this.aiSyncJobClient = aiSyncJobClient;
    }

    @Override
    public SyncJobResponse startMarketNavIncremental() {
        SyncJobResponse response = toResponse(aiSyncJobClient.startMarketNavIncremental());
        LOGGER.info(
                "InternalSyncJobService.startMarketNavIncremental   >>> sync job started, jobId={}, status={}",
                response.jobId(), response.status()
        );
        return response;
    }

    @Override
    public SyncJobResponse startMarketDetails() {
        SyncJobResponse response = toResponse(aiSyncJobClient.startMarketDetails());
        LOGGER.info(
                "InternalSyncJobService.startMarketDetails   >>> sync job started, jobId={}, status={}",
                response.jobId(), response.status()
        );
        return response;
    }

    @Override
    public SyncJobResponse getLatestMarketNavIncremental() {
        AiSyncJobStatus source = aiSyncJobClient.getLatestMarketNavIncremental();
        return source == null ? null : toResponse(source);
    }

    @Override
    public SyncJobResponse getLatestMarketDetails() {
        AiSyncJobStatus source = aiSyncJobClient.getLatestMarketDetails();
        return source == null ? null : toResponse(source);
    }

    @Override
    public List<SyncJobLastSuccessResponse> getLastSuccessfulSyncTimes() {
        return aiSyncJobClient.getLastSuccessfulSyncTimes().stream()
                .map(InternalSyncJobService::toLastSuccessResponse)
                .toList();
    }

    @Override
    public SyncJobResponse getSyncJob(UUID jobId) {
        return toResponse(aiSyncJobClient.getSyncJob(jobId));
    }

    /** 将 Python 蛇形内部契约转换为浏览器使用的驼峰响应。 */
    static SyncJobResponse toResponse(AiSyncJobStatus source) {
        return new SyncJobResponse(
                source.jobId(),
                source.jobType(),
                source.status(),
                source.requestedNavDate(),
                List.copyOf(source.fundCodes()),
                source.progressCurrent(),
                source.progressTotal(),
                source.currentFundCode(),
                source.progressMessage(),
                source.syncRunId(),
                source.fetchedCount(),
                source.createdCount(),
                source.updatedCount(),
                source.skippedCount(),
                source.errorCode(),
                source.errorMessage(),
                source.startedAt(),
                source.finishedAt()
        );
    }

    private static SyncJobLastSuccessResponse toLastSuccessResponse(AiSyncJobLastSuccess source) {
        return new SyncJobLastSuccessResponse(source.jobType(), source.lastSuccessfulAt());
    }
}
