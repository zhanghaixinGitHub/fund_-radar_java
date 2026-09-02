package com.fundradar.core.integration.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

/** M3-06 基金详情使用的只读分析摘要；读取不会触发回测、评分或发布。 */
public record AiFundAnalysisSummary(
        @JsonProperty("fund_code") String fundCode,
        @JsonProperty("fund_type") String fundType,
        @JsonProperty("availability_status") String availabilityStatus,
        String message,
        AiModelAnalysisSummary model,
        AiBacktestSummary backtest
) {
}
