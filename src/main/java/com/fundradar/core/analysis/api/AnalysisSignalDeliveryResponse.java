package com.fundradar.core.analysis.api;

import java.time.Instant;
import java.util.UUID;

/** 一次受控信号投递的安全统计，不返回任何用户身份、规则阈值或令牌。 */
public record AnalysisSignalDeliveryResponse(
        int fetchedCount,
        int processedCount,
        int signalUpsertedCount,
        int notificationCreatedCount,
        int skippedRuleCount,
        Instant lastScoredAt,
        UUID lastForecastId,
        boolean hasMore
) {
}
