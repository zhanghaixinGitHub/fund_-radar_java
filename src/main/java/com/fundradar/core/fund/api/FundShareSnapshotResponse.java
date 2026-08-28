package com.fundradar.core.fund.api;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 浏览器可展示的最新基金份额规模快照，单位与已授权来源保持一致。 */
public record FundShareSnapshotResponse(
        LocalDate tradeDate,
        BigDecimal fundShare,
        String dataSource
) {
}
