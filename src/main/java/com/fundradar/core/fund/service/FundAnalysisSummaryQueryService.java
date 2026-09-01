package com.fundradar.core.fund.service;

import com.fundradar.core.fund.api.FundAnalysisSummaryResponse;

/** 协调 M3-06 已发布模型与回测摘要的只读查询。 */
public interface FundAnalysisSummaryQueryService {

    /** 读取已发布模型摘要；不得在浏览器请求中运行回测、评分或模型发布。 */
    FundAnalysisSummaryResponse getFundAnalysisSummary(String fundCode);
}
