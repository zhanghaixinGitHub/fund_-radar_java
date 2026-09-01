package com.fundradar.core.integration.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/** Python AI 内部服务返回的可追溯 M3-G1 特征快照。 */
public record AiFeatureSnapshot(
        @JsonProperty("fund_code") String fundCode,
        @JsonProperty("as_of_date") LocalDate asOfDate,
        @JsonProperty("fund_type") String fundType,
        @JsonProperty("feature_version") String featureVersion,
        BigDecimal completeness,
        @JsonProperty("eligibility_status") String eligibilityStatus,
        @JsonProperty("unavailable_reason") String unavailableReason,
        @JsonProperty("source_code") String sourceCode,
        @JsonProperty("source_sync_finished_at") Instant sourceSyncFinishedAt,
        @JsonProperty("nav_value_basis") String navValueBasis,
        Map<String, BigDecimal> metrics,
        @JsonProperty("computed_at") Instant computedAt
) {
}
