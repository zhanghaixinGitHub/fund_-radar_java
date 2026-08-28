package com.fundradar.core.sync.service;

import com.fundradar.core.sync.api.SyncJobResponse;

import java.util.UUID;

/** 同步中心的对外业务契约，便于后续登记更多独立同步任务。 */
public interface SyncJobService {

    /** 创建基金市场净值增量同步任务。 */
    SyncJobResponse startMarketNavIncremental();

    /** 查询当前 Python 进程最近一次基金市场净值同步任务。 */
    SyncJobResponse getLatestMarketNavIncremental();

    /** 按任务标识查询同步进度或最终统计。 */
    SyncJobResponse getSyncJob(UUID jobId);
}
