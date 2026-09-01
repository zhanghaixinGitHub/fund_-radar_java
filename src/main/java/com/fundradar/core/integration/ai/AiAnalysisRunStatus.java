package com.fundradar.core.integration.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

/** Python 受控分析运行的持久状态；不包含模型输入或外部原始数据。 */
public record AiAnalysisRunStatus(
        @JsonProperty("analysis_run_id") UUID analysisRunId,
        @JsonProperty("run_type") String runType,
        String status,
        @JsonProperty("fund_type") String fundType,
        @JsonProperty("task_id") String taskId,
        @JsonProperty("backtest_run_id") UUID backtestRunId,
        @JsonProperty("model_release_id") UUID modelReleaseId,
        @JsonProperty("model_release_status") String modelReleaseStatus,
        @JsonProperty("failure_reason") String failureReason,
        @JsonProperty("requested_at") Instant requestedAt,
        @JsonProperty("started_at") Instant startedAt,
        @JsonProperty("finished_at") Instant finishedAt
) {
}
