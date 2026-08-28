package com.fundradar.core.watchlist.api;

import java.util.List;

/** 管理员受控查看指定账户的积分流水分页结果。 */
public record WatchlistCreditLedgerPageResponse(
        List<WatchlistCreditLedgerEntryResponse> items,
        int page,
        int pageSize,
        long totalCount,
        int totalPages
) {
}
