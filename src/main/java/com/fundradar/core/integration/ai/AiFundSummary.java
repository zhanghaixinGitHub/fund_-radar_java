package com.fundradar.core.integration.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;

/** Read-only fund summary returned by the internal AI service. */
public record AiFundSummary(
        @JsonProperty("fund_code") String fundCode,
        @JsonProperty("fund_name") String fundName,
        @JsonProperty("fund_type") String fundType,
        String status,
        @JsonProperty("as_of_date") LocalDate asOfDate
) {
}
