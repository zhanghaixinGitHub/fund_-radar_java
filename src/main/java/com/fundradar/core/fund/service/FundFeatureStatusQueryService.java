package com.fundradar.core.fund.service;

import com.fundradar.core.fund.api.FundFeatureStatusResponse;

/** 协调 M3-G1 特征状态查询的服务接口。 */
public interface FundFeatureStatusQueryService {

    /** 查询已持久化特征状态；不得在浏览器请求中构建特征或启动模型。 */
    FundFeatureStatusResponse getLatestFeatureStatus(String fundCode);
}
