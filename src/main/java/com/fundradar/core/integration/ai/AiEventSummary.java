package com.fundradar.core.integration.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Python AI 内部服务返回的已审核、可追溯来源的事件摘要。 */
public record AiEventSummary(
        @JsonProperty("event_id") UUID eventId,
        @JsonProperty("fund_code") String fundCode,
        @JsonProperty("event_type") String eventType,
        String summary,
        @JsonProperty("source_name") String sourceName,
        @JsonProperty("source_url") String sourceUrl,
        @JsonProperty("published_at") Instant publishedAt,
        BigDecimal confidence,
        @JsonProperty("relevance_score") BigDecimal relevanceScore,
        @JsonProperty("relation_reason") String relationReason
) {
}
