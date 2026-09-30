package com.fundradar.core.portfolio;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonFormat;

/** 本人私有安排。全部数值可空，不从投资历史推定风险承受能力；金额统一人民币元。 */
public final class AccountFundingTypes {
    private AccountFundingTypes() {}
    public enum Scope { CONFIRMED, SIMULATED }
    public record Input(String purpose, LocalDate useDate,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal requiredAmount,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal willingLossPct,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal affordableLossAmount,
            Integer reviewIntervalDays) {}
    /** expectedRevision 防止旧页面覆盖新确认；requestId 用于丢失响应后的安全重试。 */
    public record Change(UUID requestId, long expectedRevision, boolean confirmed, Input input) {}
    public record View(UUID preferenceId, Scope scope, long revision, String status, Input input, Instant confirmedAt) {}
    public record Page(Scope scope, View current, List<View> history, boolean hasMore) {}
}
