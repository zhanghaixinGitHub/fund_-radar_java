package com.fundradar.core.fund.api;

import java.time.LocalDate;

/** Java 对外基金列表中的单条摘要，字段使用前端约定的 camelCase。 */
public record FundSummaryResponse(
        String fundCode,
        String fundName,
        String fundType,
        String status,
        LocalDate asOfDate
) {
}
