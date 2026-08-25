package com.fundradar.core.fund.api;

import java.time.Instant;
import java.util.List;

/** 支持游标翻页的可追溯关联事件响应，包含安全的缓存降级标识。 */
public record FundEventPageResponse(
        List<FundEventResponse> items,
        String nextCursor,
        boolean stale,
        Instant cachedAt
) {
}
