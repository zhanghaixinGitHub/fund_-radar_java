package com.fundradar.core.fund.api;

import java.time.LocalDate;

/** Public fund-list item exposed by the Java API in camelCase. */
public record FundSummaryResponse(
        String fundCode,
        String fundName,
        String fundType,
        String status,
        LocalDate asOfDate
) {
}
