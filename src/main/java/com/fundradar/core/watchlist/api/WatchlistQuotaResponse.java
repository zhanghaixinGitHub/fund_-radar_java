package com.fundradar.core.watchlist.api;

/** 当前登录用户的关注额度；试用积分不是现金、储值或交易权益。 */
public record WatchlistQuotaResponse(
        int freeWatchlistLimit,
        long activeWatchlistCount,
        long trialCreditTotal,
        long trialCreditLocked,
        long trialCreditAvailable,
        long maxActiveWatchlistCount
) {
}
