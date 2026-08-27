package com.fundradar.core.fund.service;

import com.fundradar.core.fund.api.FundSyncResponse;
import com.fundradar.core.integration.ai.AiFocusedNavSyncResult;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 验证 Python 手动同步统计能完整映射为 Java 对外响应。 */
class InternalFundSyncServiceTests {

    @Test
    void mapsManualSyncResultWithoutChangingCountsOrFundCodes() {
        AiFocusedNavSyncResult source = new AiFocusedNavSyncResult(
                UUID.fromString("00000000-0000-0000-0000-000000000401"),
                LocalDate.of(2026, 8, 27),
                List.of("002112.OF", "010710.OF"),
                3,
                2,
                0,
                1
        );

        FundSyncResponse response = InternalFundSyncService.toResponse(source);

        assertEquals(source.syncRunId(), response.syncRunId());
        assertEquals(source.requestedNavDate(), response.requestedNavDate());
        assertEquals(source.fundCodes(), response.fundCodes());
        assertEquals(3, response.fetchedCount());
        assertEquals(2, response.createdCount());
        assertEquals(0, response.updatedCount());
        assertEquals(1, response.skippedCount());
    }
}
