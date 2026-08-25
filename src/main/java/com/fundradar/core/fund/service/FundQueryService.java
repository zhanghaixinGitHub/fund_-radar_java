package com.fundradar.core.fund.service;

import com.fundradar.core.fund.api.FundDetailResponse;
import com.fundradar.core.fund.api.FundPageResponse;

/**
 * 协调基金公开查询的服务接口。
 *
 * 隐藏 Python AI 内部服务，向 Controller 提供稳定的基金列表和详情读模型。
 */
public interface FundQueryService {

    /** 按关键字和游标查询基金分页结果，可在 AI 服务不可用时返回显式标记的缓存。 */
    FundPageResponse listFunds(String keyword, int pageSize, String cursor);

    /** 查询单只基金详情，可在 AI 服务不可用时返回显式标记的缓存。 */
    FundDetailResponse getFund(String fundCode);
}
