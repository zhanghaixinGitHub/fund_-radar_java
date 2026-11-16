package com.fundradar.core.simulation;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 本人持仓构成估算；金额为Java按当前账户与公共历史报告计算的十进制结果。 */
public final class SimulationCompositionTypes {
    private SimulationCompositionTypes() {}

    public record ReportSource(String fundCode, String fundName, String title, LocalDate endDate,
            LocalDate publishedDate, String sourceUrl, boolean fullDisclosure) {}
    public record ThemeEvidence(String sourceName, String sourceUrl, LocalDate classificationDate, String method) {}
    public record Contribution(String fundCode, String fundName,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal amount,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal weightPct,
            List<ReportSource> sources) {}
    public record Item(String key, String name, String description,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal amount,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal weightPct,
            boolean pending, List<Contribution> contributions, List<ThemeEvidence> evidence) {}
    public record ExcludedHolding(String fundCode, String fundName,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal amount, String reason) {}
    public record View(String denominator,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal totalAmount,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal coveragePct,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal mappedCoveragePct,
            LocalDate reportStart, LocalDate reportEnd, List<Item> items,
            List<ExcludedHolding> excludedHoldings) {}
    public record Composition(@JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal accountAmount,
            View assets, View industries, View themes) {}
}
