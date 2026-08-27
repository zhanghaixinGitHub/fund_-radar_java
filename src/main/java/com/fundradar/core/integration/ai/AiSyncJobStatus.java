package com.fundradar.core.integration.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Python 内部服务返回的同步中心任务状态。 */
public record AiSyncJobStatus(
        @JsonProperty("job_id") UUID jobId,
        @JsonProperty("job_type") String jobType,
        String status,
        @JsonProperty("requested_nav_date") LocalDate requestedNavDate,
        @JsonProperty("fund_codes") List<String> fundCodes,
        @JsonProperty("progress_current") int progressCurrent,
        @JsonProperty("progress_total") int progressTotal,
        @JsonProperty("current_fund_code") String currentFundCode,
        @JsonProperty("progress_message") String progressMessage,
        @JsonProperty("sync_run_id") UUID syncRunId,
        @JsonProperty("fetched_count") int fetchedCount,
        @JsonProperty("created_count") int createdCount,
        @JsonProperty("updated_count") int updatedCount,
        @JsonProperty("skipped_count") int skippedCount,
        @JsonProperty("error_code") String errorCode,
        @JsonProperty("error_message") String errorMessage,
        @JsonProperty("started_at") Instant startedAt,
        @JsonProperty("finished_at") Instant finishedAt
) {
}
