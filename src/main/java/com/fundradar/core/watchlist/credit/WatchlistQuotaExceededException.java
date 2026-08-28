package com.fundradar.core.watchlist.credit;

/** 当前有效关注数已达到免费额度与已发放试用积分共同决定的上限。 */
public class WatchlistQuotaExceededException extends RuntimeException {

    public WatchlistQuotaExceededException() {
        super("有效关注名额已用完，请先取消关注或联系管理员发放试用关注积分。");
    }
}
