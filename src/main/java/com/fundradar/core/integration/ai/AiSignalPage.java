package com.fundradar.core.integration.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Python AI 内部服务返回的 M3 评分结果游标分页。 */
public record AiSignalPage(
        List<AiSignalSummary> items,
        @JsonProperty("next_cursor") String nextCursor
) {
}
