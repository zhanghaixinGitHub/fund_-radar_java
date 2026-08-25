package com.fundradar.core.integration.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Python AI 内部服务返回的可复现 M3 评分结果。 */
public record AiSignalSummary(
        @JsonProperty("forecast_id") UUID forecastId,
        @JsonProperty("fund_code") String fundCode,
        @JsonProperty("as_of_date") LocalDate asOfDate,
        @JsonProperty("score_status") String scoreStatus,
        String direction,
        @JsonProperty("directional_probability") BigDecimal directionalProbability,
        BigDecimal confidence,
        @JsonProperty("risk_level") String riskLevel,
        @JsonProperty("max_drawdown_estimate") BigDecimal maxDrawdownEstimate,
        String explanation,
        @JsonProperty("model_version") String modelVersion,
        @JsonProperty("feature_version") String featureVersion,
        @JsonProperty("feature_completeness") BigDecimal featureCompleteness,
        @JsonProperty("scored_at") Instant scoredAt
) {
}
