package com.fundradar.core.fund.api;

import java.time.Instant;
import java.util.List;

/** 支持游标翻页的已持久化 M3 评分结果响应，包含安全的缓存降级标识。 */
public record FundSignalPageResponse(
        List<FundSignalResponse> items,
        String nextCursor,
        boolean stale,
        Instant cachedAt
) {
}
