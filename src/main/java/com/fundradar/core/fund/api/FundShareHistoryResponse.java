package com.fundradar.core.fund.api;

import java.util.List;

/** 关注后可展示的基金份额规模历史；状态区分未同步与已同步但暂无记录。 */
public record FundShareHistoryResponse(
        String status,
        List<FundShareSnapshotResponse> items
) {
}
