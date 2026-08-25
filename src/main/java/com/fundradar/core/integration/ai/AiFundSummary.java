package com.fundradar.core.integration.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;

/** Python AI 内部服务返回的只读基金摘要。 */
public record AiFundSummary(
        @JsonProperty("fund_code") String fundCode,
        @JsonProperty("fund_name") String fundName,
        @JsonProperty("fund_type") String fundType,
        String status,
        @JsonProperty("as_of_date") LocalDate asOfDate
) {
}
