package com.fundradar.core.fund.service;

import com.fundradar.core.fund.api.FundDetailResponse;
import com.fundradar.core.fund.api.FundNavHistoryResponse;
import com.fundradar.core.fund.api.FundPageResponse;
import com.fundradar.core.fund.api.FundSameTypeComparisonResponse;
import com.fundradar.core.fund.api.FundShareHistoryResponse;
import com.fundradar.core.fund.api.WatchlistFundDetailResponse;

import java.time.LocalDate;

/**
 * 协调基金公开查询的服务接口。
 *
 * 隐藏 Python AI 内部服务，向 Controller 提供稳定的基金列表和详情读模型。
 */
public interface FundQueryService {

    /** 按关键字和游标查询基金分页结果，可在 AI 服务不可用时返回显式标记的缓存。 */
    FundPageResponse listFunds(String keyword, String fundType, int pageSize, String cursor, Integer page);

    /** 查询单只基金详情，可在 AI 服务不可用时返回显式标记的缓存。 */
    FundDetailResponse getFund(String fundCode);

    /** 查询基金公共完整资料；调用方校验基金查看权限，关注页还须校验本人关注关系。 */
    WatchlistFundDetailResponse getWatchlistFundDetail(String fundCode);

    /** 查询一只基金在明确日期窗口内的历史净值，可在 AI 服务不可用时返回显式标记的缓存。 */
    FundNavHistoryResponse getFundNavHistory(String fundCode, LocalDate startDate, LocalDate endDate);

    /** 查询当前基金市场受控样本中的同类型比较，不表示全市场排名。 */
    FundSameTypeComparisonResponse getFundSameTypeComparison(String fundCode);

    /** 查询基金公共份额规模历史；与个人持有份额无关，由入口执行相应访问校验。 */
    FundShareHistoryResponse getFundShareHistory(String fundCode, LocalDate startDate, LocalDate endDate);
}
