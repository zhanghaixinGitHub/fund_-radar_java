package com.fundradar.core.integration.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Python AI 内部服务返回的基金游标分页结果。 */
public record AiFundPage(
        List<AiFundSummary> items,
        @JsonProperty("next_cursor") String nextCursor
) {
}
