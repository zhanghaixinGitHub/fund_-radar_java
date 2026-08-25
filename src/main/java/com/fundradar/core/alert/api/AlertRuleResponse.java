package com.fundradar.core.alert.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** 当前用户的资讯型提醒规则；仅用于信息提示，绝不执行交易。 */
public record AlertRuleResponse(
        UUID ruleId,
        String fundCode,
        String ruleType,
        BigDecimal threshold,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt
) {
}
