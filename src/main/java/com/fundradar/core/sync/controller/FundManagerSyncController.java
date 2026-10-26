package com.fundradar.core.sync.controller;

import com.fundradar.core.auth.CurrentUserContext;
import com.fundradar.core.auth.PermissionCode;
import com.fundradar.core.common.api.ApiResponse;
import com.fundradar.core.fund.api.FundManagerDirectory;
import com.fundradar.core.integration.ai.AiFundManagerDirectoryClient;
import com.fundradar.core.integration.ai.AiSyncJobStatus;
import com.fundradar.core.sync.api.SyncJobResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 管理员独立更新入口，与既有总批次共用任务核心；查询状态不产生来源请求。 */
@RestController
@RequestMapping("/api/v1/sync-jobs/fund-managers")
public class FundManagerSyncController {
    private final AiFundManagerDirectoryClient client;
    public FundManagerSyncController(AiFundManagerDirectoryClient client) { this.client = client; }
    @PostMapping
    public ResponseEntity<ApiResponse<SyncJobResponse>> start(@Valid @RequestBody FundManagerDirectory.SyncRequest body) {
        CurrentUserContext.requirePermission(PermissionCode.SYNC_JOB_START);
        return ResponseEntity.accepted().body(ApiResponse.success(convert(client.start(body))));
    }
    @GetMapping("/latest")
    public ApiResponse<SyncJobResponse> latest() {
        CurrentUserContext.requirePermission(PermissionCode.SYNC_JOB_READ);
        return ApiResponse.success(convert(client.latest()));
    }
    private SyncJobResponse convert(AiSyncJobStatus value) {
        if (value == null) return null;
        return new SyncJobResponse(value.jobId(), value.jobType(), value.status(), value.requestedNavDate(),
                value.fundCodes(), value.progressCurrent(), value.progressTotal(), value.currentFundCode(),
                value.progressMessage(), value.syncRunId(), value.fetchedCount(), value.createdCount(),
                value.updatedCount(), value.skippedCount(), value.errorCode(), value.errorMessage(),
                value.startedAt(), value.finishedAt(), value.resultSummary());
    }
    @ExceptionHandler(AiFundManagerDirectoryClient.DirectoryException.class)
    public ResponseEntity<ApiResponse<Void>> conflict(AiFundManagerDirectoryClient.DirectoryException error) {
        return ResponseEntity.status(error.status()).body(ApiResponse.failure("MANAGER_SYNC_UNAVAILABLE",
                error.status() == 409 ? "已有同步任务正在执行，请稍后重试。" : "所选基金经理尚未收录。"));
    }
}
