package com.fundradar.core.sync.service;

import com.fundradar.core.integration.ai.AiSyncJobStatus;
import com.fundradar.core.sync.api.SyncJobResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 验证同步中心将 Python 进度与最终统计完整映射为浏览器响应。 */
class InternalSyncJobServiceTests {

    @Test
    void mapsRunningJobWithoutInventingFinalCounts() {
        AiSyncJobStatus source = new AiSyncJobStatus(
                UUID.fromString("00000000-0000-0000-0000-000000000401"),
                "MARKET_NAV_INCREMENTAL",
                "RUNNING",
                LocalDate.of(2026, 8, 27),
                List.of("002112.OF", "010710.OF"),
                1,
                3,
                "002112.OF",
                "已读取 002112.OF 的待补齐净值",
                null,
                0,
                0,
                0,
                0,
                null,
                null,
                Instant.parse("2026-08-27T12:00:00Z"),
                null
        );

        SyncJobResponse response = InternalSyncJobService.toResponse(source);

        assertEquals(source.jobId(), response.jobId());
        assertEquals(source.status(), response.status());
        assertEquals(source.fundCodes(), response.fundCodes());
        assertEquals(1, response.progressCurrent());
        assertEquals(3, response.progressTotal());
        assertEquals("002112.OF", response.currentFundCode());
        assertNull(response.syncRunId());
        assertEquals(0, response.fetchedCount());
        assertNull(response.errorCode());
    }

    @Test
    void mapsFailedJobWithSafeActionableMessage() {
        AiSyncJobStatus source = new AiSyncJobStatus(
                UUID.fromString("00000000-0000-0000-0000-000000000402"),
                "MARKET_NAV_INCREMENTAL",
                "FAILED",
                LocalDate.of(2026, 8, 27),
                List.of("002112.OF"),
                1,
                2,
                null,
                "同步未完成",
                null,
                0,
                0,
                0,
                0,
                "MARKET_SYNC_BASELINE_MISSING",
                "请先完成基金市场历史净值回填或来源代码校验。",
                Instant.parse("2026-08-27T12:00:00Z"),
                Instant.parse("2026-08-27T12:01:00Z")
        );

        SyncJobResponse response = InternalSyncJobService.toResponse(source);

        assertEquals("FAILED", response.status());
        assertEquals("MARKET_SYNC_BASELINE_MISSING", response.errorCode());
        assertEquals("请先完成基金市场历史净值回填或来源代码校验。", response.errorMessage());
        assertEquals(source.finishedAt(), response.finishedAt());
    }

    @Test
    void mapsPartialSuccessWithoutHidingTheSourceSyncRun() {
        AiSyncJobStatus source = new AiSyncJobStatus(
                UUID.fromString("00000000-0000-0000-0000-000000000403"),
                "MARKET_NAV_INCREMENTAL",
                "PARTIAL_SUCCESS",
                LocalDate.of(2026, 9, 1),
                List.of(),
                2,
                2,
                null,
                "基金市场净值同步完成，特征快照未更新",
                UUID.fromString("00000000-0000-0000-0000-000000000304"),
                3,
                1,
                1,
                1,
                "FEATURE_SNAPSHOT_BUILD_FAILED",
                "基金市场净值已同步，但特征快照未生成，可在同步中心单独重试。",
                Instant.parse("2026-09-01T12:00:00Z"),
                Instant.parse("2026-09-01T12:01:00Z")
        );

        SyncJobResponse response = InternalSyncJobService.toResponse(source);

        assertEquals("PARTIAL_SUCCESS", response.status());
        assertEquals(source.syncRunId(), response.syncRunId());
        assertEquals("FEATURE_SNAPSHOT_BUILD_FAILED", response.errorCode());
    }

    @Test
    void mapsFreeDataCompletionParentRunWithoutInventingDatasetDetails() {
        AiSyncJobStatus source = new AiSyncJobStatus(
                UUID.fromString("00000000-0000-0000-0000-000000000404"),
                "MARKET_FREE_DATA_COMPLETION",
                "SUCCEEDED",
                LocalDate.of(2026, 9, 2),
                List.of(),
                2,
                2,
                null,
                "当前 2000 积分已授权数据补齐完成",
                UUID.fromString("00000000-0000-0000-0000-000000000306"),
                10,
                7,
                2,
                1,
                null,
                null,
                Instant.parse("2026-09-02T12:00:00Z"),
                Instant.parse("2026-09-02T12:01:00Z")
        );

        SyncJobResponse response = InternalSyncJobService.toResponse(source);

        assertEquals("MARKET_FREE_DATA_COMPLETION", response.jobType());
        assertEquals("SUCCEEDED", response.status());
        assertEquals(source.syncRunId(), response.syncRunId());
        assertEquals(10, response.fetchedCount());
    }
}
