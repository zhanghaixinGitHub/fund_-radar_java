package com.fundradar.core.fund.api;

import java.util.List;

/** Public cursor page exposed by the Java API in camelCase. */
public record FundPageResponse(
        List<FundSummaryResponse> items,
        String nextCursor
) {
}
