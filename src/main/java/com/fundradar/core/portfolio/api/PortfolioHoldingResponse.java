package com.fundradar.core.portfolio.api;

import java.math.BigDecimal;

/** 用户确认快照中的单只基金展示字段；不是实时净值、份额或交易流水。 */
public record PortfolioHoldingResponse(
        String fundCode,
        String fundName,
        BigDecimal reportedAmount,
        BigDecimal reportedWeightPct,
        BigDecimal reportedDailyGainAmount,
        BigDecimal reportedHoldingGainAmount,
        BigDecimal reportedHoldingGainPct,
        BigDecimal reportedCumulativeGainAmount
) {
}
