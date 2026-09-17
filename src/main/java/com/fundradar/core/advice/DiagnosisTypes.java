package com.fundradar.core.advice;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fundradar.core.simulation.SimulationTypes;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 持仓诊断契约：Python只给公共事实与参考结论，终判结果逐项留档；诊断不触发交易。 */
public final class DiagnosisTypes {
    private DiagnosisTypes() {}
    public static final List<String> ITEM_KEYS=List.of("MANAGER","SCALE","SAME_TYPE_RANK","BENCHMARK","DRAWDOWN","FEE","DIVIDEND");
    public static final List<String> VERDICTS=List.of("VALID","CHANGED","INSUFFICIENT");
    /** 单项诊断；存储的终判与Python参考结论同一结构，facts为结构化事实字段。 */
    @JsonIgnoreProperties(ignoreUnknown=true)
    public record FactItem(String item, String verdict, String evidence, String source,
                           LocalDate dataAsOfDate, Map<String,Object> facts) {}
    /** Python公共诊断事实响应；不含任何用户身份，verdict为参考值。 */
    @JsonIgnoreProperties(ignoreUnknown=true)
    public record Facts(String fundCode, LocalDate asOfDate, String overall, String basis, List<FactItem> items) {}
    public record Summary(UUID reportId, String fundCode, String fundName, LocalDate reportDate,
                          Instant generatedAt, String verdict, LocalDate cutoffDate) {}
    /** 报告详情含逐项items；回读时已校验content_hash。 */
    public record Detail(Summary report, List<FactItem> items) {}
    public record History(String fundCode, String fundName, SimulationTypes.Page<Summary> reports,
                          Detail latest, SimulationTypes.JobState job) {}
    /** 历史诊断日的同类排名标记；bottom为空表示当日该项数据不足，连续统计中断。 */
    public record RankDay(LocalDate reportDate, Boolean bottom) {}
}
