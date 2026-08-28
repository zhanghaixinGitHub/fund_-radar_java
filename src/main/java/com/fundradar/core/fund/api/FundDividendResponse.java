package com.fundradar.core.fund.api;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 浏览器可展示的结构化分红事件，不包含资讯或公告正文。 */
public record FundDividendResponse(
        LocalDate annDate,
        LocalDate implementationAnnDate,
        LocalDate baseDate,
        String processStatus,
        LocalDate recordDate,
        LocalDate exDate,
        LocalDate payDate,
        LocalDate earningsPayDate,
        LocalDate navExDate,
        BigDecimal cashDividend,
        BigDecimal baseUnit,
        BigDecimal distributableEarnings,
        BigDecimal earningsAmount,
        LocalDate reinvestmentArrivalDate,
        String baseYear,
        String dataSource
) {
}
