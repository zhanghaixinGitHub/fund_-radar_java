package com.fundradar.core.fund.api;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 一条已同步的基金历史净值；不代表盘中估值或收益预测。 */
public record FundNavPointResponse(
        LocalDate navDate,
        BigDecimal unitNav,
        BigDecimal accumulatedNav
) {
}
