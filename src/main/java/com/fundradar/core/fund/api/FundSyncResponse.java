package com.fundradar.core.fund.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 浏览器手动发起重点基金净值同步后返回的安全统计摘要。 */
public record FundSyncResponse(
        UUID syncRunId,
        LocalDate requestedNavDate,
        List<String> fundCodes,
        int fetchedCount,
        int createdCount,
        int updatedCount,
        int skippedCount
) {
}
