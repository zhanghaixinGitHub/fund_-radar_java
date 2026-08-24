package com.fundradar.core.fund.api;

import java.time.LocalDate;

/** Public fund-detail response exposed by the Java API in camelCase. */
public record FundDetailResponse(
        String fundCode,
        String fundName,
        String fundType,
        String status,
        LocalDate asOfDate,
        String navStatus,
        String dataSource
) {
}
