package com.fundradar.core.watchlist.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** 当前用户关注页中的一条基金展示摘要；行情字段只来自已落库净值读模型。 */
public record WatchlistFundItemResponse(
        String fundCode,
        String fundName,
        String fundType,
        LocalDate asOfDate,
        BigDecimal dayChangeRate,
        BigDecimal weekChangeRate,
        BigDecimal monthChangeRate,
        Instant createdAt
) {
}
