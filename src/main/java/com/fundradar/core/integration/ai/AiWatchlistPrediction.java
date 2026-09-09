package com.fundradar.core.integration.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Python现金研究状态的最小投影，不接收模型系数、完整样本或历史答案。
 * 概率/方向目前必须为空；研究协议不能被错误地当成已发布模型。
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
        String disclaimer
) { }
