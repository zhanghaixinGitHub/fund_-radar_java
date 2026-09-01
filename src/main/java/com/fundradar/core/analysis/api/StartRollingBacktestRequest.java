package com.fundradar.core.analysis.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;

import java.math.BigDecimal;

/** 管理员显式发起固定基线回测的受限参数；不接收基准、模型代码或外部来源。 */
public record StartRollingBacktestRequest(
        @DecimalMin(value = "0.0", message = "费率不能小于 0。")
        @DecimalMax(value = "0.999999", message = "费率必须小于 1。") BigDecimal feeRate
) {

    private static final BigDecimal DEFAULT_FEE_RATE = new BigDecimal("0.001500");

    /** 缺省时采用已评审的固定基线费率，而不是由前端临时猜测。 */
    public BigDecimal resolvedFeeRate() {
        return feeRate == null ? DEFAULT_FEE_RATE : feeRate;
    }
}
