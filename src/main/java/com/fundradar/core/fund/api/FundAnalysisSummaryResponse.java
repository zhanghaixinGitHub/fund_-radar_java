package com.fundradar.core.fund.api;

import java.time.Instant;

/** 基金详情的模型可用性与关联回测摘要；所有数据均来自已持久化的内部读模型。 */
public record FundAnalysisSummaryResponse(
        String fundCode,
        String fundType,
        String availabilityStatus,
        String message,
        FundModelAnalysisSummaryResponse model,
        FundBacktestSummaryResponse backtest,
        FundAnalysisExplanationResponse explanation,
        boolean stale,
        Instant cachedAt
) {
}
