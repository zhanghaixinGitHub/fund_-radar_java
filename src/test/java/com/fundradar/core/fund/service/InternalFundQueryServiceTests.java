package com.fundradar.core.fund.service;

import com.fundradar.core.fund.api.FundDetailResponse;
import com.fundradar.core.fund.api.FundNavHistoryResponse;
import com.fundradar.core.integration.ai.AiFundDetail;
import com.fundradar.core.integration.ai.AiFundNavHistory;
import com.fundradar.core.integration.ai.AiFundNavPoint;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** 验证 Python 内部净值字段能完整映射到 Java 对外详情契约。 */
class InternalFundQueryServiceTests {

    @Test
    void mapsLatestNavSnapshotWithoutLosingDecimalPrecision() {
        AiFundDetail source = new AiFundDetail(
                "002112",
                "德邦鑫星价值灵活配置混合C",
                "MIXED",
                "ACTIVE",
                LocalDate.of(2026, 8, 25),
                new BigDecimal("4.89360000"),
                new BigDecimal("4.89360000"),
                "SYNCED",
                "TUSHARE_PRO_FUND"
        );

        FundDetailResponse response = InternalFundQueryService.toDetailResponse(source);

        assertEquals(new BigDecimal("4.89360000"), response.unitNav());
        assertEquals(new BigDecimal("4.89360000"), response.accumulatedNav());
        assertEquals("TUSHARE_PRO_FUND", response.dataSource());
        assertFalse(response.stale());
    }

    @Test
    void mapsNavHistoryWithoutLosingDecimalPrecision() {
        AiFundNavHistory source = new AiFundNavHistory(
                "002112",
                java.util.List.of(new AiFundNavPoint(
                        LocalDate.of(2026, 8, 25), new BigDecimal("4.89360000"), new BigDecimal("5.04160000")
                ))
        );

        FundNavHistoryResponse response = InternalFundQueryService.toNavHistoryResponse(source);

        assertEquals(1, response.items().size());
        assertEquals(new BigDecimal("4.89360000"), response.items().get(0).unitNav());
        assertEquals(new BigDecimal("5.04160000"), response.items().get(0).accumulatedNav());
        assertFalse(response.stale());
    }
}
