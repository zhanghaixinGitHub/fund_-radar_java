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
    public record CalendarData(String version, LocalDate coverageStart, LocalDate coverageEnd,
                               List<LocalDate> sessions, String assumption) {}
    public record Nav(LocalDate navDate, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal unitNav, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal accumulatedNav,
                      LocalDate announcedOn, String revision) {}
    public record Dividend(String eventKey, LocalDate recordDate, LocalDate exDate, LocalDate payDate,
                           @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal cashPerUnit, boolean implemented, String revision) {}
    public record Market(String fundCode, String fundName, boolean supported, String reason, String source,
                         List<Nav> navs, List<Dividend> dividends, Instant dividendsVerifiedAt,
                         String refreshStatus, String refreshMessage) {}
    public record Execution(UUID orderId, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal shares, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal unitNav, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal grossAmount,
                            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal cost, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal realizedGain, String navRevision, String source) {}
    public record Order(UUID orderId, UUID userId, String fundCode, String fundName, String side,
                        @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal amount, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal shares, LocalDate tradeDate, LocalDate eligibleDate,
                        String status, String sourceKind, String requestKey, String requestHash,
                        Instant createdAt, Instant confirmedAt, Execution execution) {}
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
    public record Overview(List<Position> positions, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal marketValue, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal holdingGain,
                           @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal cumulativeGain, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal pendingBuyAmount, int pendingOrders,
                           boolean complete, JobState job, String rules) {}
    public record Preview(String fundCode, String fundName, boolean supported, String reason,
                          @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal referenceNav, LocalDate referenceDate, LocalDate tradeDate,
                          LocalDate eligibleDate, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal availableShares, int pendingBuys, String rules) {}
}
