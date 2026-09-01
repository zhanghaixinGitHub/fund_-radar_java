package com.fundradar.core.fund.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** 基金详情页可展示的特征数据状态；状态不可用时其余字段全部为空。 */
public record FundFeatureStatusResponse(
        String status,
        LocalDate asOfDate,
        String fundType,
        String featureVersion,
        BigDecimal completeness,
        String eligibilityStatus,
        String unavailableReason,
        String sourceCode,
        Instant sourceSyncFinishedAt,
        String navValueBasis,
        FundFeatureMetricsResponse metrics,
        Instant computedAt,
        boolean stale,
        Instant cachedAt
) {
}
