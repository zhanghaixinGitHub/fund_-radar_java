package com.fundradar.core.fund.api;

import java.time.LocalDate;
import java.util.List;

/** 仅在当前基金市场范围内计算的同类型比较结果，调用方不得宣称为全市场排名。 */
public record FundSameTypeComparisonResponse(
        String fundType,
        String scope,
        String status,
        LocalDate asOfDate,
        Integer targetRank,
        int comparableCount,
        List<FundSameTypeComparisonItemResponse> items,
        FundComparisonGroupResponse comparisonGroup,
        LocalDate comparisonStartDate,
        String sampleUnit,
        Integer comparableProductCount,
        String reason
) {
    /** 旧调用仍只表达份额对照；新增产品数没有核实前保持未知。 */
    public FundSameTypeComparisonResponse(String fundType, String scope, String status, LocalDate asOfDate,
                                          Integer targetRank, int comparableCount,
                                          List<FundSameTypeComparisonItemResponse> items) {
        this(fundType, scope, status, asOfDate, targetRank, comparableCount, items,
                null, null, "SHARE_CLASS", null, null);
    }
}
