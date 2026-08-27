package com.fundradar.core.sync.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 浏览器可读取的同步中心任务状态，不含内部凭据或外部原始数据。 */
public record SyncJobResponse(
        UUID jobId,
        String jobType,
        String status,
        LocalDate requestedNavDate,
        List<String> fundCodes,
        int progressCurrent,
        int progressTotal,
        String currentFundCode,
        String progressMessage,
        UUID syncRunId,
        int fetchedCount,
        int createdCount,
        int updatedCount,
        int skippedCount,
        String errorCode,
        String errorMessage,
        Instant startedAt,
        Instant finishedAt
) {
}
