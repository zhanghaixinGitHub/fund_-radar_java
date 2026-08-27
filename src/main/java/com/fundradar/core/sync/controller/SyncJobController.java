package com.fundradar.core.sync.controller;

import com.fundradar.core.auth.CurrentUserContext;
import com.fundradar.core.auth.PermissionCode;
import com.fundradar.core.common.api.ApiResponse;
import com.fundradar.core.sync.api.SyncJobResponse;
import com.fundradar.core.sync.service.SyncJobService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 数据同步中心的 Java 对外接口。
 *
 * <p>关联文档：docs_zhx/requirements/fund-radar.md；
 * docs_zhx/design/fund-radar.md；
 * docs_zhx/testcase/fund-radar.md。</p>
 */
@RestController
@RequestMapping("/api/v1/sync-jobs")
public class SyncJobController {

    private final SyncJobService syncJobService;

    public SyncJobController(SyncJobService syncJobService) {
        this.syncJobService = syncJobService;
    }

    /** 创建重点基金日净值增量任务，立即返回任务标识；实际执行在 Python 后台进行。 */
    @PostMapping("/focused-nav-incremental")
    public ResponseEntity<ApiResponse<SyncJobResponse>> startFocusedNavIncremental() {
        CurrentUserContext.requirePermission(PermissionCode.SYNC_JOB_START);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(syncJobService.startFocusedNavIncremental()));
    }

    /** 查询当前 Python 进程最近一次重点基金同步任务；无任务时 data 为 null。 */
    @GetMapping("/focused-nav-incremental/latest")
    public ApiResponse<SyncJobResponse> getLatestFocusedNavIncremental() {
        CurrentUserContext.requirePermission(PermissionCode.SYNC_JOB_READ);
        return ApiResponse.success(syncJobService.getLatestFocusedNavIncremental());
    }

    /** 查询指定任务的阶段、当前基金、进度及完成后写入统计。 */
    @GetMapping("/{jobId}")
    public ApiResponse<SyncJobResponse> getSyncJob(@PathVariable UUID jobId) {
        CurrentUserContext.requirePermission(PermissionCode.SYNC_JOB_READ);
        return ApiResponse.success(syncJobService.getSyncJob(jobId));
    }
}
