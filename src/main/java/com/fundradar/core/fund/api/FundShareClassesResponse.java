package com.fundradar.core.fund.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 同产品份额的公共事实；报告截至日不等于实时交易状态，也不授权混用净值。 */
public record FundShareClassesResponse(
        String fundCode, String status, LocalDate asOfDate, Instant checkedAt, List<Share> shares
) {
    /** 类别与币种仅在来源明确时填写；未知类别保留完整份额名称。 */
    public record Share(String fundCode, String fundName, String shareClass, String currency) {}
}
