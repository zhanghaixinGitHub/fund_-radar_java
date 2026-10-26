package com.fundradar.core.fund.api;

import java.time.LocalDate;
import java.util.List;

/** 同一团队管理阶段的本基金回报，全团队共用一次展示，不作为个人完整任期回报。 */
public record FundManagerPeriodResponse(
        LocalDate beginDate,
        LocalDate endDate,
        List<String> managerNames,
        FundManagerPerformanceResponse performance
) {
}
