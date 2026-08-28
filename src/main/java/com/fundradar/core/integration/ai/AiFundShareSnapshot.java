package com.fundradar.core.integration.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Python 内部服务返回的最新基金份额规模快照。 */
public record AiFundShareSnapshot(
        @JsonProperty("trade_date") LocalDate tradeDate,
        @JsonProperty("fund_share") BigDecimal fundShare,
        @JsonProperty("data_source") String dataSource
) {
}
