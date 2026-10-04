package com.fundradar.core.alert.api;

import java.util.List;

/** 本人已关注基金的提醒规则分页；总条数使用与列表相同的接收状态筛选。 */
public record AlertRulePageResponse(
        List<AlertRuleResponse> items,
        int page,
        int pageSize,
        long totalCount,
        int totalPages
) {
}
