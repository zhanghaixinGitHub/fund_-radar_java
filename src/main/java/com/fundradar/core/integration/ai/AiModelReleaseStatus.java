package com.fundradar.core.integration.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

/** Python 模型发布状态机响应；状态变化必须由管理员显式发起。 */
public record AiModelReleaseStatus(
        @JsonProperty("model_release_id") UUID modelReleaseId,
        @JsonProperty("model_code") String modelCode,
        @JsonProperty("model_version") String modelVersion,
        @JsonProperty("feature_version") String featureVersion,
        @JsonProperty("fund_type") String fundType,
        @JsonProperty("backtest_run_id") UUID backtestRunId,
        @JsonProperty("release_status") String releaseStatus,
        @JsonProperty("effective_at") Instant effectiveAt,
        @JsonProperty("suspended_at") Instant suspendedAt,
        @JsonProperty("release_reason") String releaseReason
) {
}
