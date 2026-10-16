package com.fundradar.core.fund.api;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Java 对外基金列表中的单条摘要，字段使用前端约定的 camelCase。 */
public record FundSummaryResponse(
        String fundCode,
        String fundName,
        String fundType,
        String status,
        LocalDate asOfDate,
        BigDecimal dayChangeRate,
        BigDecimal weekChangeRate,
        BigDecimal monthChangeRate,
        boolean isWatched,
        /** 随本页批量读取的已发布评级；读取失败或缺少对应结果时为 null，不代表暂无评分。 */
        FundRatingResponse.Summary rating
) {
}
