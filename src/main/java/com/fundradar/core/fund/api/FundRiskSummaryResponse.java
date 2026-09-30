package com.fundradar.core.fund.api;

import java.math.BigDecimal;
import java.util.List;

/** 已发布的公共风险事实；百分比保留披露分母，空值表示未知，不包含个人账户信息。 */
public record FundRiskSummaryResponse(String fundCode, boolean available, String asOfDate,
        String reportDate, String publishedDate, String sourceUrl, List<String> facts,
        List<Holding> holdings, List<Allocation> industries, List<Allocation> assets,
        CrossCheck crossCheck, List<String> limitations, BigDecimal disclosedWeightPct,
        BigDecimal topTenWeightPct, BigDecimal quoteCoverageWeightPct) {
    public record Holding(String stockCode, String name, BigDecimal weightPct) {}
    /** denominator 明确总资产或净资产，禁止在页面混为同一占比。 */
    public record Allocation(String name, BigDecimal weightPct, String denominator) {}
    /** 静态影响单位为百分点，只覆盖同日且可核验的行情，不表示实际收益归因。 */
    public record CrossCheck(String date, BigDecimal coverageWeightPct,
            BigDecimal staticContributionPctPoints, String summary, String limitation) {}
}
