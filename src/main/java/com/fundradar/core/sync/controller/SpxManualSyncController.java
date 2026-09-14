package com.fundradar.core.sync.controller;

import com.fundradar.core.auth.CurrentUserContext;
import com.fundradar.core.auth.PermissionCode;
import com.fundradar.core.common.api.ApiResponse;
import com.fundradar.core.integration.ai.AiSpxManualClient;
import com.fundradar.core.sync.api.SpxSyncStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 独立按需采集按钮；与“同步全部”的SPX阶段共用采集服务，沿用既有同步管理权限。 */
@RestController
@RequestMapping("/api/v1/sync-jobs/spx-manual")
public class SpxManualSyncController {
    private final AiSpxManualClient client;

    public SpxManualSyncController(AiSpxManualClient client) { this.client = client; }

    @GetMapping("/status")
    public ApiResponse<SpxSyncStatus> status() {
        CurrentUserContext.requirePermission(PermissionCode.SYNC_JOB_READ);
        return ApiResponse.success(client.status());
    }

    /** 全局拦截器先验证会话、Origin与CSRF，这里再验证启动权限；客户端不能指定历史时刻。 */
    @PostMapping("/sync")
    public ApiResponse<SpxSyncStatus> synchronize() {
        CurrentUserContext.requirePermission(PermissionCode.SYNC_JOB_START);
        return ApiResponse.success(client.synchronize());
    }
}
