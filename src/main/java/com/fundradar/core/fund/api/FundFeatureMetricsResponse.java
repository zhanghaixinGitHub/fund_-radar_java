package com.fundradar.core.fund.api;

import java.math.BigDecimal;

/** 对浏览器发布的历史净值统计特征，不包含方向、概率或置信度。 */
public record FundFeatureMetricsResponse(
        BigDecimal return5d,
        BigDecimal return20d,
        BigDecimal return60d,
        BigDecimal volatility20d,
        BigDecimal maxDrawdown60d
) {
}
