package com.fundradar.core.integration.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Python 内部服务返回的重点基金手动净值同步结果。 */
public record AiFocusedNavSyncResult(
        @JsonProperty("sync_run_id") UUID syncRunId,
        @JsonProperty("requested_nav_date") LocalDate requestedNavDate,
        @JsonProperty("fund_codes") List<String> fundCodes,
        @JsonProperty("fetched_count") int fetchedCount,
        @JsonProperty("created_count") int createdCount,
        @JsonProperty("updated_count") int updatedCount,
        @JsonProperty("skipped_count") int skippedCount
) {
}
