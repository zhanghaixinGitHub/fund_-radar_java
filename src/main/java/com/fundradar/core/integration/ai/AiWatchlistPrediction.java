package com.fundradar.core.integration.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Python现金研究/正式结果的最小投影，不接收模型系数、完整样本或历史答案。
 * 只有完整且通过校验的AVAILABLE结果可带数字，研究状态仍必须为空。
 */
public record AiWatchlistPrediction(
        @JsonProperty("fund_code") String fundCode,
        String status,
        @JsonProperty("horizon_trading_days") Integer horizonTradingDays,
        @JsonProperty("up_probability") BigDecimal upProbability,
        String direction,
        @JsonProperty("latest_nav_date") LocalDate latestNavDate,
        @JsonProperty("research_run_id") UUID researchRunId,
        @JsonProperty("research_evaluated_at") Instant researchEvaluatedAt,
        @JsonProperty("model_version") String modelVersion,
        @JsonProperty("reason_codes") List<String> reasonCodes,
        List<String> reasons,
        String message,
        String disclaimer,
        @JsonProperty("forecast_id") UUID forecastId,
        @JsonProperty("cutoff_date") LocalDate cutoffDate,
        @JsonProperty("target_base_date") LocalDate targetBaseDate,
        @JsonProperty("target_end_date") LocalDate targetEndDate,
        @JsonProperty("generated_at") Instant generatedAt,
        @JsonProperty("model_hash") String modelHash
) { }
