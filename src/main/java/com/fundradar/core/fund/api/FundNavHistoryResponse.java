package com.fundradar.core.fund.api;

import java.time.Instant;
import java.util.List;

/** 指定日期窗口的基金历史净值响应，可显式标识缓存降级状态。 */
public record FundNavHistoryResponse(
        List<FundNavPointResponse> items,
        boolean stale,
        Instant cachedAt
) {
}
