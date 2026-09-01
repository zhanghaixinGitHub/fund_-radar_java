package com.fundradar.core.analysis.controller;

import com.fundradar.core.analysis.api.AnalysisSignalDeliveryResponse;
import com.fundradar.core.analysis.api.ModelReleaseTransitionRequest;
import com.fundradar.core.analysis.api.StartRollingBacktestRequest;
import com.fundradar.core.analysis.service.AnalysisAdministrationService;
import com.fundradar.core.auth.AuthenticatedUser;
import com.fundradar.core.auth.CurrentUserContext;
import com.fundradar.core.common.api.ApiResponse;
import com.fundradar.core.integration.ai.AiAnalysisRunStatus;
import com.fundradar.core.integration.ai.AiModelReleaseStatus;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** M3-05 系统管理员控制面；仅管理分析状态，不提供交易、外部数据或浏览器直连能力。 */
@RestController
@RequestMapping("/api/v1/admin/analysis")
public class AnalysisAdministrationController {

    private final AnalysisAdministrationService analysisAdministrationService;

    public AnalysisAdministrationController(AnalysisAdministrationService analysisAdministrationService) {
        this.analysisAdministrationService = analysisAdministrationService;
    }

    /** 排队固定股票基线回测，立即返回持久运行标识。 */
    @PostMapping("/runs/rolling-backtest")
    public ResponseEntity<ApiResponse<AiAnalysisRunStatus>> startRollingBacktest(
            @Valid @RequestBody(required = false) StartRollingBacktestRequest request
    ) {
        AuthenticatedUser administrator = CurrentUserContext.requireAdministrator();
        StartRollingBacktestRequest safeRequest = request == null ? new StartRollingBacktestRequest(null) : request;
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(
                analysisAdministrationService.startRollingBacktest(safeRequest.resolvedFeeRate(), administrator)
        ));
    }

    /** 查询持久分析运行状态，不能通过读取重跑任务。 */
    @GetMapping("/runs/{analysisRunId}")
    public ApiResponse<AiAnalysisRunStatus> getAnalysisRun(@PathVariable UUID analysisRunId) {
        CurrentUserContext.requireAdministrator();
        return ApiResponse.success(analysisAdministrationService.getAnalysisRun(analysisRunId));
    }

    /** 经过人工审核后显式激活满足发布闸门的候选模型。 */
    @PostMapping("/model-releases/{modelReleaseId}/activate")
    public ApiResponse<AiModelReleaseStatus> activateModelRelease(
            @PathVariable UUID modelReleaseId,
            @Valid @RequestBody ModelReleaseTransitionRequest request
    ) {
        AuthenticatedUser administrator = CurrentUserContext.requireAdministrator();
        return ApiResponse.success(analysisAdministrationService.activateModelRelease(
                modelReleaseId, request.reason(), administrator
        ));
    }

    /** 显式暂停已发布模型，停止后续新评分，不删除历史记录。 */
    @PostMapping("/model-releases/{modelReleaseId}/suspend")
    public ApiResponse<AiModelReleaseStatus> suspendModelRelease(
            @PathVariable UUID modelReleaseId,
            @Valid @RequestBody ModelReleaseTransitionRequest request
    ) {
        AuthenticatedUser administrator = CurrentUserContext.requireAdministrator();
        return ApiResponse.success(analysisAdministrationService.suspendModelRelease(
                modelReleaseId, request.reason(), administrator
        ));
    }

    /** 管理员手动消费 Python 已发布评分；仅生成本地信息提醒。 */
    @PostMapping("/signal-delivery")
    public ApiResponse<AnalysisSignalDeliveryResponse> deliverSignals() {
        return ApiResponse.success(analysisAdministrationService.deliverSignals(CurrentUserContext.requireAdministrator()));
    }
}
