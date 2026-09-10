package com.fundradar.core.watchlist.service;

import com.fundradar.core.watchlist.api.WatchlistItemResponse;
import com.fundradar.core.watchlist.api.WatchlistPageResponse;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * 当前用户关注列表的读写服务接口。
 *
 * 负责维护关注记录及相应审计日志，不包含交易、持仓或投资建议能力。
 */
public interface WatchlistService {

    /** 在本人关注中按代码或名称关键词、类型筛选，按类型连续分组并以页码返回。 */
    WatchlistPageResponse listCurrentUserItems(String keyword, String fundType, int page, int pageSize);

    /** 从当前认证用户范围内批量判断哪些基金已关注，供基金市场和详情页追加展示状态。 */
    Set<String> findCurrentUserFollowedFundCodes(Collection<String> fundCodes);

    /** 校验基金存在后将其加入当前用户关注列表；重复提交不创建重复数据。 */
    WatchlistItemResponse addCurrentUserItem(String fundCode);

    /** 移除当前用户的基金关注记录；不存在时记录幂等审计但不报错。 */
    void removeCurrentUserItem(String fundCode);
}
