package com.fundradar.core.fund.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 已持久化的 DeepSeek 解释快照；仅供信息展示，不构成交易指令。 */
public record FundAnalysisExplanationResponse(
        UUID explanationId,
        UUID forecastId,
        LocalDate asOfDate,
        String provider,
        String providerModel,
        String promptVersion,
        String overview,
        List<FundAnalysisExplanationEvidenceResponse> evidence,
        String riskNotice,
        String dataGap,
        String disclaimer,
        Instant generatedAt
) {
}
