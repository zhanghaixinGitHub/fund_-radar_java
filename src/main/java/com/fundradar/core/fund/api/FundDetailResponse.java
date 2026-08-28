package com.fundradar.core.fund.api;

import java.time.Instant;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Java 对外基金详情响应，字段使用前端约定的 camelCase。
 *
 * stale 为 true 时表示 AI 服务暂不可用，当前数据来自 Redis 中最后一次成功读取的缓存；cachedAt 为缓存生成时间。
 */
public record FundDetailResponse(
        String fundCode,
        String fundName,
        String fundType,
        String status,
        LocalDate asOfDate,
        BigDecimal unitNav,
        BigDecimal accumulatedNav,
        LocalDate navAnnDate,
        BigDecimal accumulatedDividend,
        BigDecimal netAsset,
        BigDecimal totalNetAsset,
        BigDecimal adjustedNav,
        String navStatus,
        String dataSource,
        BigDecimal dayChangeRate,
        BigDecimal weekChangeRate,
        BigDecimal monthChangeRate,
        String profileStatus,
        String profileDataSource,
        String managementCompanyName,
        String custodianName,
        LocalDate foundDate,
        LocalDate dueDate,
        LocalDate listDate,
        LocalDate issueDate,
        LocalDate delistDate,
        BigDecimal issueAmount,
        BigDecimal managementFee,
        BigDecimal custodianFee,
        BigDecimal durationYear,
        BigDecimal parValue,
        BigDecimal minPurchaseAmount,
        BigDecimal expectedReturn,
        String benchmark,
        String investType,
        String sourceFundType,
        String trusteeName,
        LocalDate purchaseStartDate,
        LocalDate redemptionStartDate,
        String market,
        boolean isWatched,
        boolean stale,
        Instant cachedAt
) {

    /** 为原市场详情调用保留兼容构造函数；新增资料尚未同步时统一为空。 */
    public FundDetailResponse(
            String fundCode,
            String fundName,
            String fundType,
            String status,
            LocalDate asOfDate,
            BigDecimal unitNav,
            BigDecimal accumulatedNav,
            String navStatus,
            String dataSource,
            BigDecimal dayChangeRate,
            BigDecimal weekChangeRate,
            BigDecimal monthChangeRate,
            boolean isWatched,
            boolean stale,
            Instant cachedAt
    ) {
        this(
                fundCode, fundName, fundType, status, asOfDate, unitNav, accumulatedNav,
                null, null, null, null, null,
                navStatus, dataSource, dayChangeRate, weekChangeRate, monthChangeRate,
                "NOT_SYNCED", null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                isWatched, stale, cachedAt
        );
    }

    /** 仅在 Java 对浏览器输出时附加用户态和缓存状态；共享资料字段始终保持原值。 */
    public FundDetailResponse withClientState(boolean watched, boolean staleValue, Instant cachedAtValue) {
        return new FundDetailResponse(
                fundCode, fundName, fundType, status, asOfDate, unitNav, accumulatedNav,
                navAnnDate, accumulatedDividend, netAsset, totalNetAsset, adjustedNav,
                navStatus, dataSource, dayChangeRate, weekChangeRate, monthChangeRate,
                profileStatus, profileDataSource, managementCompanyName, custodianName, foundDate, dueDate,
                listDate, issueDate, delistDate, issueAmount, managementFee, custodianFee, durationYear,
                parValue, minPurchaseAmount, expectedReturn, benchmark, investType, sourceFundType, trusteeName,
                purchaseStartDate, redemptionStartDate, market, watched, staleValue, cachedAtValue
        );
    }
}
