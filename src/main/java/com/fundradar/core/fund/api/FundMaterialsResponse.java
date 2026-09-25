package com.fundradar.core.fund.api;

import java.math.BigDecimal;
import java.util.List;

/** 公共基金资料白名单；金额单位元、权重单位百分数，不含用户持仓、采集路径或模型字段。 */
public record FundMaterialsResponse(boolean available, String fundCode, String asOfDate, String notice,
        List<ReportOption> reports, Report report, List<CompanyOption> companies, Company company) {
    public record ReportOption(String id, String title, String endDate, String publishedDate,
            boolean fullDisclosure, String sourceUrl) {}
    public record Holding(String stockCode, String stockName, BigDecimal weightPct, BigDecimal marketValue) {}
    /** 资产配置占总资产；行业和股票持仓占基金净资产，页面必须分别注明分母。 */
    public record Allocation(String name, BigDecimal weightPct, BigDecimal value) {}
    public record Report(String id, String title, String endDate, String publishedDate, String sourceUrl,
            boolean fullDisclosure, BigDecimal stockWeightPct, BigDecimal disclosedWeightPct,
            List<Holding> holdings, List<Allocation> assets, List<Allocation> industries) {}
    public record CompanyOption(String stockCode, String stockName, boolean latestHeld) {}
    public record Financial(String endDate, String publishedDate, BigDecimal revenue, BigDecimal netProfit,
            BigDecimal operatingCashflow, BigDecimal totalAssets, BigDecimal totalLiabilities,
            BigDecimal revenueGrowthPct, BigDecimal netProfitGrowthPct) {}
    public record Business(String name, BigDecimal sales, String currency, String endDate) {}
    public record Disclosure(String category, String endDate, String publishedDate, String summary) {}
    public record Quote(String date, BigDecimal close, BigDecimal changePct) {}
    public record Company(String stockCode, String stockName, boolean latestHeld, List<Financial> history,
            List<Business> business, List<Disclosure> disclosures, Quote quote, String sourceName, String notice) {}
}
