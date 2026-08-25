package com.fundradar.core.fund.service;

import com.fundradar.core.fund.api.FundEventPageResponse;

/**
 * 协调可追溯关联事件查询的服务接口。
 *
 * 向 Controller 提供只含已审核摘要的读模型，不暴露 Python 服务或原始资讯内容。
 */
public interface FundEventQueryService {

    /** 按基金、页大小和游标查询已审核事件分页；返回缓存时必须显式标记 stale。 */
    FundEventPageResponse listEvents(String fundCode, int pageSize, String cursor);
}
