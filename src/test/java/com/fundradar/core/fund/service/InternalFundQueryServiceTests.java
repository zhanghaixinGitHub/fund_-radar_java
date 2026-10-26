package com.fundradar.core.fund.service;

import com.fundradar.core.fund.api.FundDetailResponse;
import com.fundradar.core.fund.api.FundNavHistoryResponse;
import com.fundradar.core.fund.api.FundPageResponse;
import com.fundradar.core.fund.api.FundSameTypeComparisonResponse;
import com.fundradar.core.fund.api.FundShareHistoryResponse;
import com.fundradar.core.integration.ai.AiFundDetail;
import com.fundradar.core.integration.ai.AiFundDividend;
import com.fundradar.core.integration.ai.AiFundManager;
import com.fundradar.core.integration.ai.AiFundNavHistory;
import com.fundradar.core.integration.ai.AiFundNavPoint;
import com.fundradar.core.integration.ai.AiFundPage;
import com.fundradar.core.integration.ai.AiFundSameTypeComparison;
import com.fundradar.core.integration.ai.AiFundSameTypeComparisonItem;
import com.fundradar.core.integration.ai.AiFundShareHistory;
import com.fundradar.core.integration.ai.AiFundShareSnapshot;
import com.fundradar.core.integration.ai.AiFundSummary;
import com.fundradar.core.integration.ai.AiFundWatchlistDetail;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void mapsFullDetailWithoutPuttingWatchStatusIntoTheInternalPayload() {
        AiFundWatchlistDetail source = new AiFundWatchlistDetail(
                new AiFundDetail(
                        "002112", "德邦鑫星价值灵活配置混合C", "MIXED", "ACTIVE",
                        LocalDate.of(2026, 8, 25), new BigDecimal("4.89360000"), new BigDecimal("5.04160000"),
                        "SYNCED", "TUSHARE_PRO_FUND", null, null, null
                ),
                "SYNCED",
                List.of(new AiFundManager(
                        "张三", LocalDate.of(2020, 1, 1), LocalDate.of(2020, 1, 1), null,
                        "硕士", "TUSHARE_PRO_FUND", new com.fundradar.core.integration.ai.AiFundManagerPerformance(
                                "AVAILABLE", new BigDecimal("0.123456789012"), LocalDate.of(2020, 1, 2),
                                LocalDate.of(2026, 8, 25), null, true, "CASH_REINVESTED_NAV_V1"
                        )
                )),
                "SYNCED",
                new AiFundShareSnapshot(LocalDate.of(2026, 8, 25), new BigDecimal("12345.6000"), "TUSHARE_PRO_FUND"),
                "SYNCED",
                List.of(new AiFundDividend(
                        LocalDate.of(2026, 8, 1), null, null, "实施", null, null, null, null, null,
                        new BigDecimal("0.0100"), null, null, null, null, "2026", "TUSHARE_PRO_FUND"
                )),
                List.of(new com.fundradar.core.integration.ai.AiFundManagerPeriod(
                        LocalDate.of(2026, 8, 1), null, List.of("张三"),
                        new com.fundradar.core.integration.ai.AiFundManagerPerformance(
                                "AVAILABLE", new BigDecimal("0.03"), LocalDate.of(2026, 8, 3),
                                LocalDate.of(2026, 8, 25), null, false, "CASH_REINVESTED_NAV_V1"
                        )
                ))
        );

        var response = InternalFundQueryService.toWatchlistFundDetailResponse(source);

        assertFalse(response.basic().isWatched());
        assertEquals("张三", response.managers().get(0).managerName());
        // 保留比例、实际净值日期和共管说明，不把收益率提前乘100或把未知值补零。
        assertEquals(new BigDecimal("0.123456789012"), response.managers().get(0).tenurePerformance().returnRate());
        assertEquals(LocalDate.of(2020, 1, 2), response.managers().get(0).tenurePerformance().startDate());
        assertEquals(LocalDate.of(2026, 8, 25), response.managers().get(0).tenurePerformance().endDate());
        assertTrue(response.managers().get(0).tenurePerformance().includesCoManagement());
        // 阶段回报独立透传，不能换成个人完整任期的0.123456789012，也不能丢失实际起算净值日。
        assertEquals(new BigDecimal("0.03"), response.managerPeriods().get(0).performance().returnRate());
        assertEquals(LocalDate.of(2026, 8, 1), response.managerPeriods().get(0).beginDate());
        assertEquals(LocalDate.of(2026, 8, 3), response.managerPeriods().get(0).performance().startDate());
        assertFalse(response.managerPeriods().get(0).performance().includesCoManagement());
        assertEquals(response.managerPeriods(), response.withWatchStatus().managerPeriods());
        var legacy = new AiFundWatchlistDetail(source.basic(), source.managersStatus(), source.managers(),
                source.latestShareStatus(), source.latestShare(), source.dividendsStatus(), source.dividends());
        assertTrue(InternalFundQueryService.toWatchlistFundDetailResponse(legacy).managerPeriods().isEmpty());
        assertEquals(new BigDecimal("12345.6000"), response.latestShare().fundShare());
        assertEquals(new BigDecimal("0.0100"), response.dividends().get(0).cashDividend());
        assertFalse(response.stale());
        assertTrue(response.withWatchStatus().basic().isWatched());
    }

    @Test
    void mapsShareHistoryWithoutLosingSourceOrDecimalPrecision() {
        AiFundShareHistory source = new AiFundShareHistory(
                "002112",
                "SYNCED",
                List.of(new AiFundShareSnapshot(
                        LocalDate.of(2026, 8, 25), new BigDecimal("12345.6000"), "TUSHARE_PRO_FUND"
                ))
        );

        FundShareHistoryResponse response = InternalFundQueryService.toShareHistoryResponse(source);

        assertEquals("SYNCED", response.status());
        assertEquals(new BigDecimal("12345.6000"), response.items().get(0).fundShare());
        assertEquals("TUSHARE_PRO_FUND", response.items().get(0).dataSource());
    }

    @Test
    void mapsSameTypeComparisonWithoutChangingControlledScope() {
        AiFundSameTypeComparison source = new AiFundSameTypeComparison(
                "002112",
                "MIXED",
                "CURRENT_MARKET_ACTIVE_TUSHARE_PRO_FUND",
                "SYNCED",
                LocalDate.of(2026, 8, 25),
                2,
                3,
                List.of(new AiFundSameTypeComparisonItem(
                        1,
                        "010710",
                        "样本基金",
                        "MIXED",
                        LocalDate.of(2026, 8, 25),
                        new BigDecimal("0.01230000"),
                        "TUSHARE_PRO_FUND"
                ))
        );

        FundSameTypeComparisonResponse response = InternalFundQueryService.toSameTypeComparisonResponse(source);

        assertEquals("CURRENT_MARKET_ACTIVE_TUSHARE_PRO_FUND", response.scope());
        assertEquals(2, response.targetRank());
        assertEquals(new BigDecimal("0.01230000"), response.items().get(0).monthChangeRate());
    }
}
