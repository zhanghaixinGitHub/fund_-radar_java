package com.fundradar.core.fund.api;

import java.time.LocalDate;

/** 浏览器可展示的基金经理任职资料，不包含简历原文。 */
public record FundManagerResponse(
        String managerName,
        LocalDate annDate,
        LocalDate beginDate,
        LocalDate endDate,
        String education,
        String dataSource
) {
}
