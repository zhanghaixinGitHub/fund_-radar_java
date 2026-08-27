package com.fundradar.core.fund.api;

import java.util.List;
import java.time.Instant;

/** 支持兼容游标与页码分页的基金列表对外响应，可安全携带缓存降级状态与缓存时间。 */
public record FundPageResponse(
        List<FundSummaryResponse> items,
        String nextCursor,
        Integer page,
        int pageSize,
        long totalCount,
        int totalPages,
        boolean stale,
        Instant cachedAt
) {
}
