package com.fundradar.core.sync.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * SPX手动采集的安全读模型，字段仅含公共行情及本机接收证据。
 * canSync由服务端间隔/互斥/契约决定；时间均为带时区的真实记录，不表示供应商首发时刻。
 */
public record SpxSyncStatus(
        String mode, String serverTime, boolean canSync, String message,
        int attemptsToday, Integer maxAttemptsPerDay, String nextAllowedAt, Attempt lastAttempt, boolean performedNow,
        String availability, String errorCode
) {
    /** 涨跌幅单位为百分比；隔夜变化仅在中国交易日所需日期齐全时存在，不能解释为预测收益。 */
    public record Attempt(
            String attemptId, String state, String message, String requestedAt,
            String receivedAt, String persistedAt, String targetDate, String latestUsDate,
            BigDecimal close, BigDecimal previousClose, BigDecimal dailyChangePct,
            BigDecimal overnightChangePct, int rowCount, List<String> missingUsDates,
            boolean usableBeforeU08,
            // 真实阶段与已完成步骤；包装类型兼容旧回执，错误码仅来自服务端安全白名单。
            String stage, String stageLabel, Integer completedSteps, Integer totalSteps, String errorCode
    ) { }
}
