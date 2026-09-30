package com.fundradar.core.fund.api;

import java.math.BigDecimal;
import java.util.List;

/** 公共历史评价的资料状态；未获准发布时分数、排名及评分日均为空，不能补零。 */
public record FundEvaluationStatusResponse(List<Item> items) {
    public record Item(String fundCode,BigDecimal score,Integer rank,String scoreDate,
            String coverageCheckedAt,String navAsOfDate,String message,List<String> reasons,String note) {}
}
