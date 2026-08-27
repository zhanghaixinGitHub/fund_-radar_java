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
                "FOCUSED_NAV_INCREMENTAL",
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
                "FOCUSED_NAV_INCREMENTAL",
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
                "FOCUSED_SYNC_BASELINE_MISSING",
                "请先完成重点基金历史净值回填。",
                Instant.parse("2026-08-27T12:00:00Z"),
                Instant.parse("2026-08-27T12:01:00Z")
        );

        SyncJobResponse response = InternalSyncJobService.toResponse(source);

        assertEquals("FAILED", response.status());
        assertEquals("FOCUSED_SYNC_BASELINE_MISSING", response.errorCode());
        assertEquals("请先完成重点基金历史净值回填。", response.errorMessage());
        assertEquals(source.finishedAt(), response.finishedAt());
    }
}
