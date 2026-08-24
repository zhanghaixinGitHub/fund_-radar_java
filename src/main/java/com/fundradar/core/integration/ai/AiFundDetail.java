package com.fundradar.core.integration.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;

/** Read-only fund detail returned by the internal AI service. */
public record AiFundDetail(
        @JsonProperty("fund_code") String fundCode,
        @JsonProperty("fund_name") String fundName,
        @JsonProperty("fund_type") String fundType,
        String status,
        @JsonProperty("as_of_date") LocalDate asOfDate,
        @JsonProperty("nav_status") String navStatus,
        @JsonProperty("data_source") String dataSource
) {
}
