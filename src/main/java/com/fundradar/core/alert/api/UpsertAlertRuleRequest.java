package com.fundradar.core.alert.api;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

/** 当前用户创建或更新资讯型提醒规则的请求体。 */
public record UpsertAlertRuleRequest(
        @NotBlank(message = "基金代码不能为空。")
        @Pattern(regexp = "^\\d{6}$", message = "基金代码必须为 6 位数字。") String fundCode,
        @NotBlank(message = "提醒类型不能为空。")
        @Pattern(
                regexp = "^(RISK_LEVEL|SIGNAL_CHANGE|EVENT)$",
                message = "提醒类型仅支持 RISK_LEVEL、SIGNAL_CHANGE 或 EVENT。"
        ) String ruleType,
        @DecimalMin(value = "0.0", message = "风险阈值不能小于 0。")
        @DecimalMax(value = "1.0", message = "风险阈值不能大于 1。") BigDecimal threshold,
        @NotNull(message = "启用状态不能为空。") Boolean enabled
) {

    /**
     * 校验风险阈值与提醒类型是否匹配。
     *
     * 仅 RISK_LEVEL 规则必须提供 0 到 1 的阈值；其他类型必须不提供阈值，避免语义混淆。
     */
    @AssertTrue(message = "RISK_LEVEL 必须提供 0 到 1 的阈值，其他提醒类型不得提供阈值。")
    public boolean isThresholdCompatibleWithRuleType() {
        if ("RISK_LEVEL".equals(ruleType)) {
            return threshold != null;
        }
        return threshold == null;
    }
}
