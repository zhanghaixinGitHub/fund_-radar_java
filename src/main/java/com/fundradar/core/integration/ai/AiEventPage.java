package com.fundradar.core.integration.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Python AI 内部服务返回的已审核事件摘要游标分页结果。 */
public record AiEventPage(
        List<AiEventSummary> items,
        @JsonProperty("next_cursor") String nextCursor
) {
}
