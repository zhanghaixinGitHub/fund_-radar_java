package com.fundradar.core.integration.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Cursor page returned by the internal AI service. */
public record AiFundPage(
        List<AiFundSummary> items,
        @JsonProperty("next_cursor") String nextCursor
) {
}
