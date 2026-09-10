package com.fundradar.core.integration.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 实验推理专用内部契约，不接收或返回正式上涨概率。 */
public record AiDirectionExperiment(
        @JsonProperty("fund_code") String fundCode,
        String version, String status,
        @JsonProperty("model_released") Boolean modelReleased,
        @JsonProperty("horizon_trading_days") Integer horizonTradingDays,
        @JsonProperty("research_run_id") UUID researchRunId,
        @JsonProperty("cutoff_date") LocalDate cutoffDate,
        @JsonProperty("latest_nav_date") LocalDate latestNavDate,
        @JsonProperty("target_base_date") LocalDate targetBaseDate,
        @JsonProperty("target_end_date") LocalDate targetEndDate,
        @JsonProperty("read_at") Instant readAt,
        @JsonProperty("input_hash") String inputHash,
        @JsonProperty("source_revision_id") UUID sourceRevisionId,
        List<ModelScore> models,
        @JsonProperty("reason_codes") List<String> reasonCodes,
        String message
) {
    public record ModelScore(String branch, Double score, String direction,
            @JsonProperty("model_hash") String modelHash,
            @JsonProperty("fit_end") LocalDate fitEnd) { }
}
