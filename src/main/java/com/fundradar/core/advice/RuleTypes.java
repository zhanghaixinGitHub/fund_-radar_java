package com.fundradar.core.advice;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 持仓规则草案与已确认规则契约；阈值只产生复核提示，不承诺触发后走势，不构成投资建议。 */
public final class RuleTypes {
    private RuleTypes() {}
    public static final String VERSION="HOLDING_RULE_V1";
    public static final List<String> TIERS=List.of("CONSERVATIVE","BALANCED","LOOSE");
    public static final List<String> DRAFT_STATUS=List.of("AVAILABLE","DATA_INSUFFICIENT","NOT_APPLICABLE");
    /** 一条阈值的历史触发统计；末端无法完整观察的触发记censored，不补造数值。 */
    @JsonIgnoreProperties(ignoreUnknown=true)
    public record TriggerStats(int triggerCount,
                               @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal medianFurtherDecline,
                               @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal medianRecoveryDays,
                               int censoredCount) {}
    /** 一档草案阈值；语义仅为「达到后提示复核」。 */
    @JsonIgnoreProperties(ignoreUnknown=true)
    public record DraftTier(String tier,
                            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal reduceDrawdownPct,
                            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal takeProfitPct,
                            TriggerStats reduceTrigger, TriggerStats takeProfitTrigger) {}
    /** Python草案统计响应；数据不足或不适用时只给status与reason，不给阈值数字。 */
    @JsonIgnoreProperties(ignoreUnknown=true)
    public record DraftStats(String fundCode, String status, String reason, LocalDate statsCutoffDate,
                             int historyDays, int windowCount, String navBasis, Map<String,Object> stats,
                             List<DraftTier> tiers, String assumption, String basis) {}
    /** 草案视图；未被归档的数据不足结果draftId与generatedAt为空，且不给阈值数字。 */
    public record DraftView(UUID draftId, String fundCode, String status, String reason, LocalDate statsCutoffDate,
                            Instant generatedAt, Integer historyDays, Integer windowCount, String navBasis,
                            Map<String,Object> stats, List<DraftTier> tiers, String assumption) {}
    public record RuleView(UUID ruleId, String fundCode, String tier,
                           @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal takeProfitPct,
                           @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal reduceDrawdownPct,
                           Map<String,Object> ruleParams, UUID sourceDraftId, String ruleVersion,
                           String status, Instant confirmedAt, Instant supersededAt) {}
    public record RulesView(String fundCode, RuleView active, List<RuleView> history) {}
    /** 确认请求：tier为标准档位；两个阈值缺省用草案分位值，微调限草案值±20%并记CUSTOM。 */
    public record ConfirmRequest(String tier,
                                 @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal takeProfitPct,
                                 @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal reduceDrawdownPct) {}
    /** 已归档草案行；fingerprint用于统计未变幂等。 */
    public record DraftRow(UUID draftId, Instant generatedAt, LocalDate statsCutoffDate,
                           Map<String,Object> stats, List<DraftTier> tiers, String fingerprint) {}
}
