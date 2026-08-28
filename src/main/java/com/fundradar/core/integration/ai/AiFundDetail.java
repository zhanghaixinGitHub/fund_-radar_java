package com.fundradar.core.integration.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Python AI 内部服务返回的只读基金详情。 */
public record AiFundDetail(
        @JsonProperty("fund_code") String fundCode,
        @JsonProperty("fund_name") String fundName,
        @JsonProperty("fund_type") String fundType,
        String status,
        @JsonProperty("as_of_date") LocalDate asOfDate,
        @JsonProperty("unit_nav") BigDecimal unitNav,
        @JsonProperty("accumulated_nav") BigDecimal accumulatedNav,
        @JsonProperty("nav_status") String navStatus,
        @JsonProperty("data_source") String dataSource,
        @JsonProperty("day_change_rate") BigDecimal dayChangeRate,
        @JsonProperty("week_change_rate") BigDecimal weekChangeRate,
        @JsonProperty("month_change_rate") BigDecimal monthChangeRate
) {
}
