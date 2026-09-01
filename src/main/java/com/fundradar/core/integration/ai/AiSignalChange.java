package com.fundradar.core.integration.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** 可投递的 ACTIVE 模型评分变更；仅用于资讯提示，不构成交易指令。 */
public record AiSignalChange(
        @JsonProperty("forecast_id") UUID forecastId,
        @JsonProperty("fund_code") String fundCode,
        @JsonProperty("as_of_date") LocalDate asOfDate,
        @JsonProperty("model_version") String modelVersion,
        @JsonProperty("feature_version") String featureVersion,
        @JsonProperty("model_release_id") UUID modelReleaseId,
        String direction,
        @JsonProperty("directional_probability") BigDecimal directionalProbability,
        BigDecimal confidence,
        @JsonProperty("risk_level") String riskLevel,
        @JsonProperty("max_drawdown_estimate") BigDecimal maxDrawdownEstimate,
        String explanation,
        @JsonProperty("feature_completeness") BigDecimal featureCompleteness,
        @JsonProperty("scored_at") Instant scoredAt
) {
}
