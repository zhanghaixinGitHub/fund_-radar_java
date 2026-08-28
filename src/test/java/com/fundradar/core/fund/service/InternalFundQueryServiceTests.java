package com.fundradar.core.fund.service;

import com.fundradar.core.fund.api.FundDetailResponse;
import com.fundradar.core.fund.api.FundNavHistoryResponse;
import com.fundradar.core.fund.api.FundPageResponse;
import com.fundradar.core.integration.ai.AiFundDetail;
import com.fundradar.core.integration.ai.AiFundNavHistory;
import com.fundradar.core.integration.ai.AiFundNavPoint;
import com.fundradar.core.integration.ai.AiFundPage;
import com.fundradar.core.integration.ai.AiFundSummary;
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
                "TUSHARE_PRO_FUND",
                new BigDecimal("0.01000000"),
                new BigDecimal("0.02000000"),
                new BigDecimal("0.03000000")
        );

        FundDetailResponse response = InternalFundQueryService.toDetailResponse(source);

        assertEquals(new BigDecimal("4.89360000"), response.unitNav());
        assertEquals(new BigDecimal("4.89360000"), response.accumulatedNav());
        assertEquals("TUSHARE_PRO_FUND", response.dataSource());
        assertEquals(new BigDecimal("0.01000000"), response.dayChangeRate());
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

    @Test
    void mapsPageMetadataForDirectPageJump() {
        AiFundPage source = new AiFundPage(
                java.util.List.of(new AiFundSummary(
                        "010710", "安信医药健康主题股票C", "STOCK", "ACTIVE", LocalDate.of(2026, 8, 26),
                        new BigDecimal("0.01000000"), new BigDecimal("0.02000000"), new BigDecimal("0.03000000")
                )),
                null,
                3,
                10,
                43,
                5
        );

        FundPageResponse response = InternalFundQueryService.toPageResponse(source);

        assertEquals(3, response.page());
        assertEquals(10, response.pageSize());
        assertEquals(43, response.totalCount());
        assertEquals(5, response.totalPages());
        assertEquals("010710", response.items().get(0).fundCode());
        assertEquals(new BigDecimal("0.02000000"), response.items().get(0).weekChangeRate());
        assertFalse(response.stale());
    }
}
