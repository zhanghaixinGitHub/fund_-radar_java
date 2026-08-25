package com.fundradar.core.watchlist.api;

import java.time.Instant;

/** 当前本地用户范围内的一条公开关注记录。 */
public record WatchlistItemResponse(
        String fundCode,
        Instant createdAt
) {
}
