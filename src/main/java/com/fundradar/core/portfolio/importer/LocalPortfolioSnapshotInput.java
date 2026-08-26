package com.fundradar.core.portfolio.importer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 本机文件导入的严格字段契约；该文件不应提交到 Git。 */
public record LocalPortfolioSnapshotInput(
        String sourceKind,
        String dataAsOfStatus,
        LocalDate dataAsOfDate,
        String sourceDescription,
        List<LocalPortfolioHoldingInput> holdings
) {

    /** 单只基金的截图可见展示值；无份额、成本或交易时间字段时不得推算。 */
    public record LocalPortfolioHoldingInput(
            String fundCode,
            String fundName,
            BigDecimal reportedAmount,
            BigDecimal reportedWeightPct,
            BigDecimal reportedDailyGainAmount,
            BigDecimal reportedHoldingGainAmount,
            BigDecimal reportedHoldingGainPct,
            BigDecimal reportedCumulativeGainAmount
    ) {
    }
}
