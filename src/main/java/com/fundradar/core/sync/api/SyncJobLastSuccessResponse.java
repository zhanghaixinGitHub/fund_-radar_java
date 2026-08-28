package com.fundradar.core.sync.api;

import java.time.Instant;

/** 浏览器展示一类同步任务最近一次完整成功的时间。 */
public record SyncJobLastSuccessResponse(
        String jobType,
        Instant lastSuccessfulAt
) {
}
