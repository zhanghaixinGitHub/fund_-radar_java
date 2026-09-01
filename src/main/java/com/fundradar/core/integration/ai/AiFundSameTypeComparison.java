package com.fundradar.core.integration.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;
import java.util.List;

/** Python 内部服务返回的当前基金市场范围内同类型比较。 */
public record AiFundSameTypeComparison(
        @JsonProperty("fund_code") String fundCode,
        @JsonProperty("fund_type") String fundType,
        String scope,
        String status,
        @JsonProperty("as_of_date") LocalDate asOfDate,
        @JsonProperty("target_rank") Integer targetRank,
        @JsonProperty("comparable_count") int comparableCount,
        List<AiFundSameTypeComparisonItem> items
) {
}
