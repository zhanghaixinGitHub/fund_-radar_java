package com.fundradar.core.fund.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 包含模型与特征版本的概率型评分对外响应。
 *
 * 该对象只提供信息展示，绝不是交易指令；数据不足或不适用时方向性字段必须为空。
 */
public record FundSignalResponse(
        UUID forecastId,
        LocalDate asOfDate,
        String scoreStatus,
        String direction,
        BigDecimal directionalProbability,
        BigDecimal confidence,
        String riskLevel,
        BigDecimal maxDrawdownEstimate,
        String explanation,
        String modelVersion,
        String featureVersion,
        BigDecimal featureCompleteness,
        Instant scoredAt
) {
}
