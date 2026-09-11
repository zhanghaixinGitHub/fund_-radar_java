package com.fundradar.core.advice;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fundradar.core.simulation.SimulationTypes;
import com.fundradar.core.watchlist.api.DirectionExperimentResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 持仓建议、原始依据与后续核验契约；建议不触发交易。 */
public final class AdviceTypes {
    private AdviceTypes() {}
    /** relation为SUPPORT/AGAINST/CONTEXT；未来新闻、公告、政策沿用同一来源结构。 */
    public record Evidence(String type, String relation, String title, String content,
                           String source, LocalDate sourceDate, String sourceUrl) {}
    /** 将生成时所有依据和个人持仓固定保存；页面回看不重新推理。 */
    public record Snapshot(String ruleVersion, String decision, String summary, List<Evidence> evidence,
                           List<String> limitations, SimulationTypes.Position position,
                           DirectionExperimentResponse experiment, LocalDate observationStart, LocalDate observationEnd) {}
    public record Summary(UUID reportId, String fundCode, String fundName, LocalDate reportDate, Instant generatedAt,
                          String decision, String summary, String ruleVersion, LocalDate cutoffDate,
                          LocalDate observationStart, LocalDate observationEnd, UUID originalReportId,
                          String reviewStatus, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal totalReturn, String support) {}
    /** Python只返回公共价格证据；总回报保持十进制，不转换成个人金额。 */
    public record Outcome(String fundCode, LocalDate startDate, LocalDate endDate, String status, Instant checkedAt,
                          @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal totalReturn, String message,
                          String basis, String source, String evidenceHash, Map<String,Object> evidence) {}
    public record Detail(Summary report, Snapshot snapshot, Outcome outcome) {}
    public record Stats(long reports, long samples, long assessed, long supported, long unsupported, long flat,
                        long pending, long carried, long noAdvice, String ruleVersion) {}
    public record History(String fundCode, String fundName, SimulationTypes.Page<Summary> reports,
                          Stats stats, SimulationTypes.JobState job) {}
    public record Pending(UUID reportId, String fundCode, String decision, LocalDate start, LocalDate end) {}
}
