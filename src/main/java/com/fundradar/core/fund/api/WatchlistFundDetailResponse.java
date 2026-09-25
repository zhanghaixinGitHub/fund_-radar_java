package com.fundradar.core.fund.api;

import java.time.Instant;
import java.util.List;

/** 基金公共完整资料；市场与关注共用，关注关系仅在 Java 输出时附加。 */
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
        return withWatchStatus(true);
    }

    /** 不把本人的关注标记写入公共资料存储；市场未关注用户仍可查看基金事实。 */
    public WatchlistFundDetailResponse withWatchStatus(boolean followed) {
        return new WatchlistFundDetailResponse(
                basic.withClientState(followed, stale, cachedAt),
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
