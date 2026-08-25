package com.fundradar.core.watchlist.service;

import com.fundradar.core.watchlist.api.WatchlistItemResponse;

import java.util.List;

/**
 * 当前用户关注列表的读写服务接口。
 *
 * 负责维护关注记录及相应审计日志，不包含交易、持仓或投资建议能力。
 */
public interface WatchlistService {

    /** 查询当前用户已关注的基金，按创建时间倒序返回。 */
    List<WatchlistItemResponse> listCurrentUserItems();

    /** 校验基金存在后将其加入当前用户关注列表；重复提交不创建重复数据。 */
    WatchlistItemResponse addCurrentUserItem(String fundCode);

    /** 移除当前用户的基金关注记录；不存在时记录幂等审计但不报错。 */
    void removeCurrentUserItem(String fundCode);
}
