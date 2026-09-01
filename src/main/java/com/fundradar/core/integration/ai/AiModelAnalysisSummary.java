package com.fundradar.core.integration.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

/** Python 内部服务返回的已发布或暂停模型版本摘要，不包含候选模型配置。 */
public record AiModelAnalysisSummary(
        @JsonProperty("model_release_id") UUID modelReleaseId,
        @JsonProperty("model_version") String modelVersion,
        @JsonProperty("feature_version") String featureVersion,
        @JsonProperty("release_status") String releaseStatus,
        @JsonProperty("effective_at") Instant effectiveAt,
        @JsonProperty("suspended_at") Instant suspendedAt
) {
}
