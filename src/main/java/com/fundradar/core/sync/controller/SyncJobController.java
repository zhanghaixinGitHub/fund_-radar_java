package com.fundradar.core.sync.controller;

import com.fundradar.core.auth.CurrentUserContext;
import com.fundradar.core.auth.PermissionCode;
import com.fundradar.core.common.api.ApiResponse;
import com.fundradar.core.sync.api.SyncJobLastSuccessResponse;
import com.fundradar.core.sync.api.SyncJobResponse;
import com.fundradar.core.sync.service.SyncJobService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 数据同步中心的 Java 对外接口。
 *
 * <p>关联文档：docs_zhx/requirements/sync-center-tasks.md；
 * docs_zhx/design/sync-center-tasks.md；
 * docs_zhx/testcase/sync-center-tasks.md；
 * C:/WebStormProject/workSpace05/docs_zhx/requirements/tushare-2000-data-completion.md；
 * C:/WebStormProject/workSpace05/docs_zhx/design/free-data-prediction-v1.md；
 * C:/WebStormProject/workSpace05/docs_zhx/implementation/tushare-2000-data-completion.md；
 * C:/WebStormProject/workSpace05/docs_zhx/testcase/tushare-2000-data-completion.md。</p>
 */
@RestController
@RequestMapping("/api/v1/sync-jobs")
public class SyncJobController {

    private final SyncJobService syncJobService;

    public SyncJobController(SyncJobService syncJobService) {
        this.syncJobService = syncJobService;
    }

    /** 创建基金市场日净值增量任务，立即返回任务标识；实际执行在 Python 后台进行。 */
    @PostMapping("/market-nav-incremental")
    public ResponseEntity<ApiResponse<SyncJobResponse>> startMarketNavIncremental() {
        CurrentUserContext.requirePermission(PermissionCode.SYNC_JOB_START);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(syncJobService.startMarketNavIncremental()));
    }

    /** 创建完整资料同步任务；实际执行在 Python 后台进行。 */
    @PostMapping("/market-details")
    public ResponseEntity<ApiResponse<SyncJobResponse>> startMarketDetails() {
        CurrentUserContext.requirePermission(PermissionCode.SYNC_JOB_START);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(syncJobService.startMarketDetails()));
    }

    /**
     * 创建当前 2000 积分已验权的免费数据补齐任务；仅管理员可显式发起，详情页不会调用本接口。
     */
    @PostMapping("/market-free-data-completion")
    public ResponseEntity<ApiResponse<SyncJobResponse>> startMarketFreeDataCompletion() {
        CurrentUserContext.requirePermission(PermissionCode.SYNC_JOB_START);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(syncJobService.startMarketFreeDataCompletion()));
    }

    /** 创建特征快照同步任务；该任务只读取已落库净值，不发起外部市场调用。 */
    @PostMapping("/stock-feature-snapshots")
    public ResponseEntity<ApiResponse<SyncJobResponse>> startStockFeatureSnapshots() {
        CurrentUserContext.requirePermission(PermissionCode.SYNC_JOB_START);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(syncJobService.startStockFeatureSnapshots()));
    }

    /** 查询当前 Python 进程最近一次基金市场同步任务；无任务时 data 为 null。 */
    @GetMapping("/market-nav-incremental/latest")
    public ApiResponse<SyncJobResponse> getLatestMarketNavIncremental() {
        CurrentUserContext.requirePermission(PermissionCode.SYNC_JOB_READ);
        return ApiResponse.success(syncJobService.getLatestMarketNavIncremental());
    }

    /** 查询当前 Python 进程最近一次完整资料同步任务；无任务时 data 为 null。 */
    @GetMapping("/market-details/latest")
    public ApiResponse<SyncJobResponse> getLatestMarketDetails() {
        CurrentUserContext.requirePermission(PermissionCode.SYNC_JOB_READ);
        return ApiResponse.success(syncJobService.getLatestMarketDetails());
    }

    /** 查询当前 Python 进程最近一次免费数据补齐任务；无任务时 data 为 null。 */
    @GetMapping("/market-free-data-completion/latest")
    public ApiResponse<SyncJobResponse> getLatestMarketFreeDataCompletion() {
        CurrentUserContext.requirePermission(PermissionCode.SYNC_JOB_READ);
        return ApiResponse.success(syncJobService.getLatestMarketFreeDataCompletion());
    }

    /** 查询当前 Python 进程最近一次特征快照任务；无任务时 data 为 null。 */
    @GetMapping("/stock-feature-snapshots/latest")
    public ApiResponse<SyncJobResponse> getLatestStockFeatureSnapshots() {
        CurrentUserContext.requirePermission(PermissionCode.SYNC_JOB_READ);
        return ApiResponse.success(syncJobService.getLatestStockFeatureSnapshots());
    }

    /** 查询各类任务最近一次完整成功的持久化时间。 */
    @GetMapping("/last-success")
    public ApiResponse<List<SyncJobLastSuccessResponse>> getLastSuccessfulSyncTimes() {
        CurrentUserContext.requirePermission(PermissionCode.SYNC_JOB_READ);
        return ApiResponse.success(syncJobService.getLastSuccessfulSyncTimes());
    }

    /** 查询指定任务的阶段、当前基金、进度及完成后写入统计。 */
    @GetMapping("/{jobId}")
    public ApiResponse<SyncJobResponse> getSyncJob(@PathVariable UUID jobId) {
        CurrentUserContext.requirePermission(PermissionCode.SYNC_JOB_READ);
        return ApiResponse.success(syncJobService.getSyncJob(jobId));
    }
}
