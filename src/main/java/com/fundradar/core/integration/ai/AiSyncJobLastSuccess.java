package com.fundradar.core.integration.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/** Python 内部服务返回的一类同步任务最近成功时间。 */
public record AiSyncJobLastSuccess(
        @JsonProperty("job_type") String jobType,
        @JsonProperty("last_successful_at") Instant lastSuccessfulAt
) {
}
