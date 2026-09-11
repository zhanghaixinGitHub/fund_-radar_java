package com.fundradar.core.sync.service;

import com.fundradar.core.sync.api.SyncJobResponse;
import com.fundradar.core.sync.api.SyncJobLastSuccessResponse;

import java.util.List;
import java.util.UUID;

/** 同步中心的对外业务契约，便于后续登记更多独立同步任务。 */
public interface SyncJobService {

    /** 创建覆盖同步中心四类任务的后台串行批次。 */
    SyncJobResponse startAll();

    /** 查询当前 Python 进程最近一键同步批次。 */
    SyncJobResponse getLatestAll();

    /** 创建基金市场净值增量同步任务。 */
    SyncJobResponse startMarketNavIncremental();

    /** 创建基金市场完整资料同步任务。 */
    SyncJobResponse startMarketDetails();

    /** 创建当前 2000 积分已授权免费数据补齐任务。 */
    SyncJobResponse startMarketFreeDataCompletion();

    /** 创建已落库净值的特征快照手动重试任务。 */
    SyncJobResponse startStockFeatureSnapshots();

    /** 查询当前 Python 进程最近一次基金市场净值同步任务。 */
    SyncJobResponse getLatestMarketNavIncremental();

    /** 查询当前 Python 进程最近一次基金市场完整资料同步任务。 */
    SyncJobResponse getLatestMarketDetails();

    /** 查询当前 Python 进程最近一次免费数据补齐任务。 */
    SyncJobResponse getLatestMarketFreeDataCompletion();

    /** 查询当前 Python 进程最近一次特征快照任务。 */
    SyncJobResponse getLatestStockFeatureSnapshots();

    /** 查询每类同步任务最近一次完整成功的持久化时间。 */
    List<SyncJobLastSuccessResponse> getLastSuccessfulSyncTimes();

    /** 按任务标识查询同步进度或最终统计。 */
    SyncJobResponse getSyncJob(UUID jobId);
}
