package com.fundradar.core.fund.api;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 经理任期内本基金份额的分红再投回报，不是个人投资收益或经理贡献评分。 */
public record FundManagerPerformanceResponse(
        String status,
        BigDecimal returnRate,
        LocalDate startDate,
        LocalDate endDate,
        String reason,
        boolean includesCoManagement,
        String calculationBasis
) {
}
