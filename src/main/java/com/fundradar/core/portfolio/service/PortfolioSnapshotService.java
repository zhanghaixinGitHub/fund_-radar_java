package com.fundradar.core.portfolio.service;

import com.fundradar.core.portfolio.api.PortfolioSnapshotResponse;

import java.util.UUID;

/** 隐藏本机持仓快照存储细节的只读服务边界。 */
public interface PortfolioSnapshotService {

    /** 返回当前认证用户的最新确认快照；没有数据时返回 unavailable 状态。 */
    PortfolioSnapshotResponse getCurrentUserSnapshot();

    /** 返回指定用户的最新确认快照；仅服务端已授权的系统管理员可以调用。 */
    PortfolioSnapshotResponse getUserSnapshot(UUID userId);
}
