package com.fundradar.core.integration.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Python AI 内部服务返回的基金兼容游标/页码分页结果。 */
public record AiFundPage(
        List<AiFundSummary> items,
        @JsonProperty("next_cursor") String nextCursor,
        Integer page,
        @JsonProperty("page_size") int pageSize,
        @JsonProperty("total_count") long totalCount,
        @JsonProperty("total_pages") int totalPages
) {
}
