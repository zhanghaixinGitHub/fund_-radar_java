package com.fundradar.core.integration.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;

/** Python 基准登记与覆盖摘要；不包含原始日序列或来源凭证。 */
public record AiBenchmarkSeriesStatus(
        @JsonProperty("benchmark_code") String benchmarkCode,
        @JsonProperty("display_name") String displayName,
        @JsonProperty("fund_type") String fundType,
        @JsonProperty("source_code") String sourceCode,
        @JsonProperty("source_enabled") boolean sourceEnabled,
        String status,
        @JsonProperty("license_reference") String licenseReference,
        @JsonProperty("point_count") int pointCount,
        @JsonProperty("first_nav_date") LocalDate firstNavDate,
        @JsonProperty("last_nav_date") LocalDate lastNavDate
) {
}
