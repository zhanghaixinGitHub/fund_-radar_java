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
        List<AiFundSameTypeComparisonItem> items,
        @JsonProperty("comparison_group") AiFundComparisonGroup comparisonGroup,
        @JsonProperty("comparison_start_date") LocalDate comparisonStartDate,
        @JsonProperty("sample_unit") String sampleUnit,
        @JsonProperty("comparable_product_count") Integer comparableProductCount,
        String reason
) {
    /** 兼容旧响应和既有调用；缺失新增字段不冒充已经完成产品去重。 */
    public AiFundSameTypeComparison(String fundCode, String fundType, String scope, String status,
                                    LocalDate asOfDate, Integer targetRank, int comparableCount,
                                    List<AiFundSameTypeComparisonItem> items) {
        this(fundCode, fundType, scope, status, asOfDate, targetRank, comparableCount, items,
                null, null, "SHARE_CLASS", null, null);
    }
}
