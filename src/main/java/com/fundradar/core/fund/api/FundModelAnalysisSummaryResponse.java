package com.fundradar.core.fund.api;

import java.time.Instant;
import java.util.UUID;

/** 基金详情可披露的发布模型版本状态；候选模型始终不会映射为该对象。 */
public record FundModelAnalysisSummaryResponse(
        UUID modelReleaseId,
        String modelVersion,
        String featureVersion,
        String releaseStatus,
        Instant effectiveAt,
        Instant suspendedAt
) {
}
