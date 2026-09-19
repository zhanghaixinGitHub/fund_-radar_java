package com.fundradar.core.simulation;

import java.math.BigDecimal;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 模拟交易专用契约；金额使用十进制，个人数据只存在 Java。 */
public final class SimulationTypes {
    private SimulationTypes() {}
    /** 模拟交易与费用口径版本：V1 无费（历史订单重放必须保持不变），V2 计申购费与分档赎回费。 */
    public static final String RULE_V1 = "CN_NAV_SIM_V1_NO_FEE";
    public static final String RULE_V2 = "CN_NAV_SIM_V2_FEE";
    public record CalendarData(String version, LocalDate coverageStart, LocalDate coverageEnd,
                               List<LocalDate> sessions, String assumption) {}
    public record Nav(LocalDate navDate, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal unitNav, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal accumulatedNav,
                      LocalDate announcedOn, String revision) {}
    public record Dividend(String eventKey, LocalDate recordDate, LocalDate exDate, LocalDate payDate,
                           @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal cashPerUnit, boolean implemented, String revision) {}
    public record Market(String fundCode, String fundName, boolean supported, String reason, String source,
                         List<Nav> navs, List<Dividend> dividends, Instant dividendsVerifiedAt,
                         String refreshStatus, String refreshMessage) {}
    /** 单档赎回费率区间；minDays 含当天，maxDays 为空表示无上限（最高档）。 */
    public record FeeBand(int minDays, Integer maxDays, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal rate) {}
    /** 单基金费率表：purchaseRate 为申购费率（无规则时为零），redeem 为按持有自然天数的赎回分档。 */
    public record FeeSchedule(@JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal purchaseRate, List<FeeBand> redeem) {}
    /** 天天基金 f10 解析出的单基金费率档案；金额分档申购只取第一档优惠费率。 */
    public record FundFee(String fundCode, String fundName,
                          @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal purchaseRate,
                          @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal purchaseOriginalRate,
                          String discountInfo, List<FeeBand> redeemBands, String dataSource) {}
    /** sim_fee_rule 管理列表行；含已终止的历史版本。 */
    public record FeeRuleRow(long ruleId, String fundCode, String fundName, String feeType, Integer minDays, Integer maxDays,
                             @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal rate, String discountInfo, String dataSource,
                             LocalDate effectiveFrom, LocalDate effectiveTo, int version, Instant updatedAt) {}
    /** 后台全量初始化费率抓取的结果汇总；失败项只记录基金与原因，不中断其余基金。 */
    public record FeeInitResult(int total, int updated, List<String> failures) {}
    /** 人工维护费率请求；version 为乐观锁版本，与行当前版本不一致时拒绝更新。 */
    public record FeeUpdateRequest(@JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal rate, int version) {}
    /** 执行结果证据；V1 口径订单的 fee/netAmount/ruleVersion 为空且不序列化，保证历史 JSON 不变。 */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record Execution(UUID orderId, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal shares, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal unitNav, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal grossAmount,
                            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal cost, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal realizedGain, String navRevision, String source,
                            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal fee, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal netAmount, String ruleVersion) {}
    public record Order(UUID orderId, UUID userId, String fundCode, String fundName, String side,
                        @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal amount, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal shares, LocalDate tradeDate, LocalDate eligibleDate,
                        String status, String sourceKind, String requestKey, String requestHash,
                        Instant createdAt, Instant confirmedAt, Execution execution, String ruleVersion) {}
    public record Position(String fundCode, String fundName, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal shares, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal frozenShares,
                           @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal availableShares, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal cost, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal marketValue,
                           @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal holdingGain, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal holdingGainRate, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal realizedGain,
                           @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal dividendGain, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal receivableDividend, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal paidDividend,
                           @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal cumulativeGain, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal dailyGain, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal totalBuy,
                           @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal totalSell, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal unitNav, LocalDate navDate, String issue) {}
    public record Daily(LocalDate date, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal marketValue, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal cumulativeGain, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal dailyGain) {}
    public record DividendEntry(String eventKey, LocalDate exDate, LocalDate payDate,
                                @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal shares, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal cashPerUnit, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal amount,
                                String revision, boolean paid) {}
    public record Calculation(Position position, List<Execution> executions, List<Daily> daily,
                              List<DividendEntry> dividends) {}
    public record Plan(UUID planId, UUID userId, String fundCode, String fundName, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal amount,
                       String frequency, int dayValue, LocalDate startDate, LocalDate endDate, Integer maxPeriods,
                       LocalDate scheduledDate, LocalDate executionDate, String status, int version,
                       UUID requestKey, Instant createdAt, long orderedPeriods, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal investedAmount) {}
    public record Period(UUID executionId, UUID planId, LocalDate scheduledDate, LocalDate executionDate,
                         String status, UUID orderId, String message, Instant createdAt) {}
    public record Page<T>(List<T> items, int page, int pageSize, long totalCount) {}
    public record JobState(String status, Instant attemptedAt, Instant completedAt, String message) {}
    /** 管理员手动补录定投的结果；按前一交易日净值补入一期，统计只含本次实际处理。 */
    public record RecurringRunResult(Instant ranAt, int plansChecked, int ordersCreated, int plansSkipped, String message) {}
    public record Overview(List<Position> positions, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal marketValue, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal holdingGain,
                           @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal cumulativeGain, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal dailyGain, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal pendingBuyAmount, int pendingOrders,
                           boolean complete, JobState job, String rules) {}
    public record Preview(String fundCode, String fundName, boolean supported, String reason,
                          @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal referenceNav, LocalDate referenceDate, LocalDate tradeDate,
                          LocalDate eligibleDate, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal availableShares, int pendingBuys, String rules) {}
}
