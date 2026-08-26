package com.fundradar.core.portfolio.service;

import com.fundradar.core.portfolio.api.PortfolioSnapshotResponse;

/** 隐藏本机持仓快照存储细节的只读服务边界。 */
public interface PortfolioSnapshotService {

    /** 返回当前本机用户的最新确认快照；没有数据时返回 unavailable 状态。 */
    PortfolioSnapshotResponse getCurrentUserSnapshot();
}
