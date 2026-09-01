package com.fundradar.core.analysis.controller;

import com.fundradar.core.analysis.api.AnalysisSignalDeliveryResponse;
import com.fundradar.core.analysis.api.BenchmarkPointImportRequest;
import com.fundradar.core.analysis.api.BenchmarkRegistrationRequest;
import com.fundradar.core.analysis.api.ModelReleaseTransitionRequest;
import com.fundradar.core.analysis.api.StartRollingBacktestRequest;
import com.fundradar.core.analysis.api.StartFundExplanationRequest;
import com.fundradar.core.analysis.service.AnalysisAdministrationService;
import com.fundradar.core.auth.AuthenticatedUser;
import com.fundradar.core.auth.CurrentUserContext;
import com.fundradar.core.common.api.ApiResponse;
import com.fundradar.core.integration.ai.AiAnalysisRunStatus;
import com.fundradar.core.integration.ai.AiBenchmarkSeriesStatus;
import com.fundradar.core.integration.ai.AiModelReleaseStatus;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** M3-05 系统管理员控制面；仅管理分析状态，不提供交易、外部数据或浏览器直连能力。 */
@RestController
@RequestMapping("/api/v1/admin/analysis")
public class AnalysisAdministrationController {

    private final AnalysisAdministrationService analysisAdministrationService;

    public AnalysisAdministrationController(AnalysisAdministrationService analysisAdministrationService) {
        this.analysisAdministrationService = analysisAdministrationService;
    }

    /**
     * 排队固定股票候选模型回测，立即返回持久运行标识。
     * 关联：docs_zhx/requirements/m3-decision-assistance.md、docs_zhx/design/m3-decision-assistance.md、
     * docs_zhx/testcase/m3-decision-assistance.md。
     */
    @PostMapping("/runs/rolling-backtest")
    public ResponseEntity<ApiResponse<AiAnalysisRunStatus>> startRollingBacktest(
            @Valid @RequestBody(required = false) StartRollingBacktestRequest request
    ) {
        AuthenticatedUser administrator = CurrentUserContext.requireAdministrator();
        StartRollingBacktestRequest safeRequest = request == null ? new StartRollingBacktestRequest(null) : request;
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(
                analysisAdministrationService.startRollingBacktest(
                        safeRequest.resolvedFeeRate(), safeRequest.resolvedBenchmarkCode(), administrator
                )
        ));
    }

    /**
     * 排队已发布评分的 DeepSeek V4-Pro 解释，不能改变数值评分、回测或模型发布状态。
     * 关联：docs_zhx/requirements/m3-decision-assistance.md、docs_zhx/design/m3-decision-assistance.md、
     * docs_zhx/testcase/m3-decision-assistance.md。
     */
    @PostMapping("/runs/fund-explanations")
    public ResponseEntity<ApiResponse<AiAnalysisRunStatus>> startFundExplanation(
            @Valid @RequestBody StartFundExplanationRequest request
    ) {
        AuthenticatedUser administrator = CurrentUserContext.requireAdministrator();
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(
                analysisAdministrationService.startFundExplanation(request.fundCode(), administrator)
        ));
    }

    /**
     * 列出股票候选回测基准的来源、状态和覆盖摘要。
     * 关联：docs_zhx/requirements/m3-decision-assistance.md、docs_zhx/design/m3-decision-assistance.md、
     * docs_zhx/testcase/m3-decision-assistance.md。
     */
    @GetMapping("/benchmarks")
    public ApiResponse<List<AiBenchmarkSeriesStatus>> listStockBenchmarks() {
        CurrentUserContext.requireAdministrator();
        return ApiResponse.success(analysisAdministrationService.listStockBenchmarks());
    }

    /**
     * 登记候选基准元数据；不会启用来源、导入点位或激活模型。
     * 关联：docs_zhx/requirements/m3-decision-assistance.md、docs_zhx/design/m3-decision-assistance.md、
     * docs_zhx/testcase/m3-decision-assistance.md。
     */
    @PutMapping("/benchmarks/{benchmarkCode}")
    public ApiResponse<AiBenchmarkSeriesStatus> registerStockBenchmark(
            @PathVariable String benchmarkCode,
            @Valid @RequestBody BenchmarkRegistrationRequest request
    ) {
        AuthenticatedUser administrator = CurrentUserContext.requireAdministrator();
        return ApiResponse.success(analysisAdministrationService.registerStockBenchmark(
                benchmarkCode,
                request.displayName(),
                request.sourceCode(),
                request.licenseReference(),
                administrator
        ));
    }

    /**
     * 导入人工核验的基准日收盘点；ACTIVE 基准必须先暂停，避免变更已运行口径。
     * 关联：docs_zhx/requirements/m3-decision-assistance.md、docs_zhx/design/m3-decision-assistance.md、
     * docs_zhx/testcase/m3-decision-assistance.md。
     */
    @PutMapping("/benchmarks/{benchmarkCode}/points")
    public ApiResponse<AiBenchmarkSeriesStatus> importStockBenchmarkPoints(
            @PathVariable String benchmarkCode,
            @Valid @RequestBody BenchmarkPointImportRequest request
    ) {
        AuthenticatedUser administrator = CurrentUserContext.requireAdministrator();
        return ApiResponse.success(analysisAdministrationService.importStockBenchmarkPoints(
                benchmarkCode, request.points(), administrator
        ));
    }

    /**
     * 启用满足来源授权和最小覆盖要求的基准；模型发布仍需独立人工审核。
     * 关联：docs_zhx/requirements/m3-decision-assistance.md、docs_zhx/design/m3-decision-assistance.md、
     * docs_zhx/testcase/m3-decision-assistance.md。
     */
    @PostMapping("/benchmarks/{benchmarkCode}/activate")
    public ApiResponse<AiBenchmarkSeriesStatus> activateStockBenchmark(@PathVariable String benchmarkCode) {
        return ApiResponse.success(
                analysisAdministrationService.activateStockBenchmark(
                        benchmarkCode, CurrentUserContext.requireAdministrator()
                )
        );
    }

    /**
     * 暂停基准以阻止新的回测使用；历史运行和模型发布记录保持可读。
     * 关联：docs_zhx/requirements/m3-decision-assistance.md、docs_zhx/design/m3-decision-assistance.md、
     * docs_zhx/testcase/m3-decision-assistance.md。
     */
    @PostMapping("/benchmarks/{benchmarkCode}/suspend")
    public ApiResponse<AiBenchmarkSeriesStatus> suspendStockBenchmark(@PathVariable String benchmarkCode) {
        return ApiResponse.success(
                analysisAdministrationService.suspendStockBenchmark(
                        benchmarkCode, CurrentUserContext.requireAdministrator()
                )
        );
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
