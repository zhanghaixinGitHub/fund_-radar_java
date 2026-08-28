package com.fundradar.core.watchlist.api;

import java.time.Instant;

/** 管理员查看的单条试用关注积分流水；不返回手机号、用户 UUID 或关联基金代码。 */
public record WatchlistCreditLedgerEntryResponse(
        String entryType,
        int creditDelta,
        String reason,
        String actorDisplayName,
        Instant createdAt
) {
}
