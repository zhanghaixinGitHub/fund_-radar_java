package com.fundradar.core.integration.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Python 内部服务返回的结构化分红事件，不包含公告或资讯正文。 */
public record AiFundDividend(
        @JsonProperty("ann_date") LocalDate annDate,
        @JsonProperty("implementation_ann_date") LocalDate implementationAnnDate,
        @JsonProperty("base_date") LocalDate baseDate,
        @JsonProperty("process_status") String processStatus,
        @JsonProperty("record_date") LocalDate recordDate,
        @JsonProperty("ex_date") LocalDate exDate,
        @JsonProperty("pay_date") LocalDate payDate,
        @JsonProperty("earnings_pay_date") LocalDate earningsPayDate,
        @JsonProperty("nav_ex_date") LocalDate navExDate,
        @JsonProperty("cash_dividend") BigDecimal cashDividend,
        @JsonProperty("base_unit") BigDecimal baseUnit,
        @JsonProperty("distributable_earnings") BigDecimal distributableEarnings,
        @JsonProperty("earnings_amount") BigDecimal earningsAmount,
        @JsonProperty("reinvestment_arrival_date") LocalDate reinvestmentArrivalDate,
        @JsonProperty("base_year") String baseYear,
        @JsonProperty("data_source") String dataSource
) {
}
