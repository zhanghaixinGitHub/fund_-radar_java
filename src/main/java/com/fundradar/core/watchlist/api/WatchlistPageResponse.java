package com.fundradar.core.watchlist.api;

import java.util.List;

/** 当前用户关注基金的类型分组分页读模型。 */
public record WatchlistPageResponse(
        List<WatchlistFundItemResponse> items,
        int page,
        int pageSize,
        long totalCount,
        int totalPages,
        boolean marketDataUnavailable,
        WatchlistQuotaResponse quota
) {
}
