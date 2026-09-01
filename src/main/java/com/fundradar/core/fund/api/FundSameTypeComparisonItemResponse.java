package com.fundradar.core.fund.api;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 当前受控基金市场样本内的一条同类型比较事实，不表示全市场排名。 */
public record FundSameTypeComparisonItemResponse(
        int rank,
        String fundCode,
        String fundName,
        String fundType,
        LocalDate asOfDate,
        BigDecimal monthChangeRate,
        String dataSource
) {
}
