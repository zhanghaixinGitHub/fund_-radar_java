package com.fundradar.core.analysis.api;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 验证回测入口仅使用既定默认费率或经过校验的明确费率。 */
class StartRollingBacktestRequestTests {

    @Test
    void usesReviewedDefaultFeeRateWhenRequestOmitsFeeRate() {
        assertEquals(new BigDecimal("0.001500"), new StartRollingBacktestRequest(null).resolvedFeeRate());
    }

    @Test
    void retainsExplicitFeeRateWithoutIntroducingOtherModelParameters() {
        BigDecimal feeRate = new BigDecimal("0.002000");
        assertEquals(feeRate, new StartRollingBacktestRequest(feeRate).resolvedFeeRate());
    }
}
