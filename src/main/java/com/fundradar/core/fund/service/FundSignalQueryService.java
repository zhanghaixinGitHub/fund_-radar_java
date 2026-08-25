package com.fundradar.core.fund.service;

import com.fundradar.core.fund.api.FundSignalPageResponse;

/**
 * 协调可复现评分结果查询的服务接口。
 *
 * 只返回已持久化结果，不启动 AI 作业，也不会向浏览器暴露后台任务能力。
 */
public interface FundSignalQueryService {

    /** 按基金、页大小和游标查询评分结果分页；返回缓存时必须显式标记 stale。 */
    FundSignalPageResponse listSignals(String fundCode, int pageSize, String cursor);
}
