package com.fundradar.core.analysis.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

/** 管理员显式发起固定候选模型回测；仅可选择已登记的本地授权基准。 */
public record StartRollingBacktestRequest(
        @DecimalMin(value = "0.0", message = "费率不能小于 0。")
        @DecimalMax(value = "0.999999", message = "费率必须小于 1。") BigDecimal feeRate,
        @Pattern(
                regexp = "^[A-Za-z0-9][A-Za-z0-9._-]{1,63}$",
                message = "基准代码格式不正确。"
        ) String benchmarkCode
) {

    private static final BigDecimal DEFAULT_FEE_RATE = new BigDecimal("0.001500");

    /** 保持旧调用兼容；未选择基准时 Python 会生成明确的不可发布回测记录。 */
    public StartRollingBacktestRequest(BigDecimal feeRate) {
        this(feeRate, null);
    }

    /** 缺省时采用已评审的固定基线费率，而不是由前端临时猜测。 */
    public BigDecimal resolvedFeeRate() {
        return feeRate == null ? DEFAULT_FEE_RATE : feeRate;
    }

    /** 空白基准与未选择一致，避免传递无意义配置。 */
    public String resolvedBenchmarkCode() {
        return benchmarkCode == null || benchmarkCode.isBlank() ? null : benchmarkCode.trim().toUpperCase();
    }
}
