package com.fundradar.core.integration.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fundradar.core.fund.api.FundRatingResponse;

import java.util.List;
import java.util.Map;

/** Python AI 内部服务返回的基金兼容游标/页码分页结果。 */
public record AiFundPage(
        List<AiFundSummary> items,
        @JsonProperty("next_cursor") String nextCursor,
        Integer page,
        @JsonProperty("page_size") int pageSize,
        @JsonProperty("total_count") long totalCount,
        @JsonProperty("total_pages") int totalPages,
        Map<String, FundRatingResponse.Summary> ratings
) {
    /** 兼容无评级筛选的分页响应；null 与已完成筛选但结果为空的空 Map 含义不同。 */
    public AiFundPage(List<AiFundSummary> items, String nextCursor, Integer page,
                      int pageSize, long totalCount, int totalPages) {
        this(items, nextCursor, page, pageSize, totalCount, totalPages, null);
    }
}
