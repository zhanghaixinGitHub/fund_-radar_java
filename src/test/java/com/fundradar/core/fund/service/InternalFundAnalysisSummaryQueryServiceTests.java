package com.fundradar.core.fund.service;

import com.fundradar.core.integration.ai.AiBacktestSummary;
import com.fundradar.core.integration.ai.AiFundAnalysisSummary;
import com.fundradar.core.integration.ai.AiModelAnalysisSummary;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 验证 M3-06 只映射已发布模型与回测白名单字段。 */
class InternalFundAnalysisSummaryQueryServiceTests {

    @Test
    void mapsActivePublishedModelAndBacktestWithoutStaleState() {
        AiFundAnalysisSummary source = new AiFundAnalysisSummary(
                "000001", "STOCK", "ACTIVE", "模型已发布。",
                new AiModelAnalysisSummary(
                        UUID.fromString("00000000-0000-0000-0000-000000000401"), "baseline-v1", "feature-v1",
                        "ACTIVE", Instant.parse("2026-09-01T00:00:00Z"), null
                ),
                new AiBacktestSummary(
                        UUID.fromString("00000000-0000-0000-0000-000000000402"), "COMPLETED", "ELIGIBLE",
                        LocalDate.of(2024, 1, 1), LocalDate.of(2026, 8, 31), LocalDate.of(2026, 1, 1),
                        LocalDate.of(2026, 8, 31), LocalDate.of(2026, 8, 31), new BigDecimal("0.001500"),
                        120, 3, new BigDecimal("0.1000"), new BigDecimal("0.1200"), new BigDecimal("0.0800"),
                        new BigDecimal("0.5500"), new BigDecimal("0.0300"), new BigDecimal("0.0200"),
                        "NOT_CONFIGURED", null, Instant.parse("2026-09-01T00:00:00Z")
                )
        );

        var response = InternalFundAnalysisSummaryQueryService.toResponse(source);

        assertEquals("ACTIVE", response.availabilityStatus());
        assertEquals("baseline-v1", response.model().modelVersion());
        assertEquals("ELIGIBLE", response.backtest().publicationStatus());
        assertEquals(new BigDecimal("0.1200"), response.backtest().maxDrawdown());
        assertFalse(response.stale());
        assertNull(response.cachedAt());
    }

    @Test
    void keepsUnpublishedModelUnavailableWithoutModelOrBacktestFields() {
        var response = InternalFundAnalysisSummaryQueryService.toResponse(
                new AiFundAnalysisSummary("000001", "STOCK", "MODEL_UNAVAILABLE", "暂无已发布模型。", null, null)
        );

        assertEquals("MODEL_UNAVAILABLE", response.availabilityStatus());
        assertNull(response.model());
        assertNull(response.backtest());
    }
}
