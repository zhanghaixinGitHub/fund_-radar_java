package com.fundradar.core.fund.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** 浏览器可读的已发布模型回测摘要，不包含候选配置、原始样本或失败堆栈。 */
public record FundBacktestSummaryResponse(
        UUID runId,
        String status,
        String publicationStatus,
        LocalDate windowStart,
        LocalDate windowEnd,
        LocalDate testStart,
        LocalDate testEnd,
        LocalDate dataCutoff,
        BigDecimal feeRate,
        Integer sampleCount,
        Integer rollingFoldCount,
        BigDecimal annualizedReturn,
        BigDecimal maxDrawdown,
        BigDecimal volatility,
        BigDecimal hitRate,
        BigDecimal longHoldResult,
        BigDecimal dcaResult,
        String benchmarkStatus,
        BigDecimal benchmarkResult,
        Instant completedAt
) {
}
