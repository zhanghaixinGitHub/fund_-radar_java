package com.fundradar.core.integration.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Python 内部服务返回的一条受控同类型比较项。 */
public record AiFundSameTypeComparisonItem(
        int rank,
        @JsonProperty("fund_code") String fundCode,
        @JsonProperty("fund_name") String fundName,
        @JsonProperty("fund_type") String fundType,
        @JsonProperty("as_of_date") LocalDate asOfDate,
        @JsonProperty("month_change_rate") BigDecimal monthChangeRate,
        @JsonProperty("data_source") String dataSource
) {
}
