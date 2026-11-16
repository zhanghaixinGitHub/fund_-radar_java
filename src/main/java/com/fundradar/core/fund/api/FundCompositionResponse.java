package com.fundradar.core.fund.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Python公共持仓构成白名单；权重单位为百分数，不包含任何用户或账户金额。 */
public record FundCompositionResponse(int version, List<Fund> funds, List<ThemeDefinition> themeDefinitions) {
    public record Fund(String fundCode, boolean available, AssetReport assets, IndustryReport industries,
            ThemeReport themes, String warning) {}
    public record Weight(String key, String name, BigDecimal weightPct) {}
    public record ReportSource(String fundCode, String fundName, String title, LocalDate endDate,
            LocalDate publishedDate, String sourceUrl, boolean fullDisclosure) {}
    public record AssetReport(String fundCode, String fundName, String title, LocalDate endDate,
            LocalDate publishedDate, String sourceUrl, boolean fullDisclosure, List<Weight> items) {}
    public record IndustryReport(String fundCode, String fundName, String title, LocalDate endDate,
            LocalDate publishedDate, String sourceUrl, boolean fullDisclosure, BigDecimal stockWeightPct,
            List<Weight> items) {}
    public record ThemeDefinition(String id, String name, String definition) {}
    public record ThemeAssignment(String id, String name, String sourceName, String sourceUrl,
            LocalDate classificationDate, String method) {}
    public record ThemeHolding(String stockCode, String stockName, BigDecimal weightPct, String sourceFundCode,
            List<ThemeAssignment> themes) {}
    public record ThemeReport(LocalDate endDate, LocalDate publishedDate, boolean fullDisclosure,
            BigDecimal disclosedWeightPct, List<ThemeHolding> holdings, List<ReportSource> sources,
            boolean lookthrough, String themeVersion) {}
}
