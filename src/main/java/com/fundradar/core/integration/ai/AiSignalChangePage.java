package com.fundradar.core.integration.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Python 按 `(scored_at, forecast_id)` 顺序返回的增量评分页。 */
public record AiSignalChangePage(
        List<AiSignalChange> items,
        @JsonProperty("has_more") boolean hasMore,
        @JsonProperty("next_scored_at") Instant nextScoredAt,
        @JsonProperty("next_forecast_id") UUID nextForecastId
) {
}
