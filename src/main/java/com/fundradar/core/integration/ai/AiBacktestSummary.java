package com.fundradar.core.integration.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Python 内部服务返回的已发布模型关联回测白名单摘要。 */
public record AiBacktestSummary(
        @JsonProperty("run_id") UUID runId,
        String status,
        @JsonProperty("publication_status") String publicationStatus,
        @JsonProperty("window_start") LocalDate windowStart,
        @JsonProperty("window_end") LocalDate windowEnd,
        @JsonProperty("test_start") LocalDate testStart,
        @JsonProperty("test_end") LocalDate testEnd,
        @JsonProperty("data_cutoff") LocalDate dataCutoff,
        @JsonProperty("fee_rate") BigDecimal feeRate,
        @JsonProperty("sample_count") Integer sampleCount,
        @JsonProperty("rolling_fold_count") Integer rollingFoldCount,
        @JsonProperty("annualized_return") BigDecimal annualizedReturn,
        @JsonProperty("max_drawdown") BigDecimal maxDrawdown,
        BigDecimal volatility,
        @JsonProperty("hit_rate") BigDecimal hitRate,
        @JsonProperty("long_hold_result") BigDecimal longHoldResult,
        @JsonProperty("dca_result") BigDecimal dcaResult,
        @JsonProperty("benchmark_status") String benchmarkStatus,
        @JsonProperty("benchmark_result") BigDecimal benchmarkResult,
        @JsonProperty("completed_at") Instant completedAt
) {
}
