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
        String navStatus,
        String dataSource,
        BigDecimal dayChangeRate,
        BigDecimal weekChangeRate,
        BigDecimal monthChangeRate,
        boolean isWatched,
        boolean stale,
        Instant cachedAt
) {
}
