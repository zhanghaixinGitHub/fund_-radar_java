package com.fundradar.core.fund.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 可公开展示的关联事件卡片。
 *
 * 保留来源可追溯信息和非因果的关联说明，不包含原始资讯正文，也不表达投资结论。
 */
public record FundEventResponse(
        UUID eventId,
        String eventType,
        String summary,
        String sourceName,
        String sourceUrl,
        Instant publishedAt,
        BigDecimal confidence,
        BigDecimal relevanceScore,
        String relationReason
) {
}
