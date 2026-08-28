package com.fundradar.core.fund.api;

import java.time.Instant;
import java.util.List;

/** 仅在服务端确认当前用户已关注后返回的完整基金详情。 */
public record WatchlistFundDetailResponse(
        FundDetailResponse basic,
        String managersStatus,
        List<FundManagerResponse> managers,
        String latestShareStatus,
        FundShareSnapshotResponse latestShare,
        String dividendsStatus,
        List<FundDividendResponse> dividends,
        boolean stale,
        Instant cachedAt
) {

    /** 仅在 Java 对浏览器输出时标记该用户已关注，避免把用户态传给 Python。 */
    public WatchlistFundDetailResponse withWatchStatus() {
        return new WatchlistFundDetailResponse(
                basic.withClientState(true, stale, cachedAt),
                managersStatus,
                managers,
                latestShareStatus,
                latestShare,
                dividendsStatus,
                dividends,
                stale,
                cachedAt
        );
    }
}
