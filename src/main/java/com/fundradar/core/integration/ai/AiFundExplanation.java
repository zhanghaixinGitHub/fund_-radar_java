package com.fundradar.core.integration.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Python 已持久化的 DeepSeek 解释快照；读取该对象不会触发外部模型调用。 */
public record AiFundExplanation(
        @JsonProperty("explanation_id") UUID explanationId,
        @JsonProperty("forecast_id") UUID forecastId,
        @JsonProperty("as_of_date") LocalDate asOfDate,
        String provider,
        @JsonProperty("provider_model") String providerModel,
        @JsonProperty("prompt_version") String promptVersion,
        String overview,
        List<AiFundExplanationEvidence> evidence,
        @JsonProperty("risk_notice") String riskNotice,
        @JsonProperty("data_gap") String dataGap,
        String disclaimer,
        @JsonProperty("generated_at") Instant generatedAt
) {
}
