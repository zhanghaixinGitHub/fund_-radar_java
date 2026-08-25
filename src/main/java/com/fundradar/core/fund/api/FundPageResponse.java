package com.fundradar.core.fund.api;

import java.util.List;
import java.time.Instant;

/** 支持游标翻页的基金列表对外响应，可安全携带缓存降级状态与缓存时间。 */
public record FundPageResponse(
        List<FundSummaryResponse> items,
        String nextCursor,
        boolean stale,
        Instant cachedAt
) {
}
