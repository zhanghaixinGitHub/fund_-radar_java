package com.fundradar.core.analysis.service;

import com.fundradar.core.analysis.api.AnalysisSignalDeliveryResponse;
import com.fundradar.core.analysis.api.BenchmarkNavPointRequest;
import com.fundradar.core.auth.AuthenticatedUser;
import com.fundradar.core.common.trace.TraceContext;
import com.fundradar.core.integration.ai.AiAnalysisClient;
import com.fundradar.core.integration.ai.AiAnalysisRunStatus;
import com.fundradar.core.integration.ai.AiBenchmarkSeriesStatus;
import com.fundradar.core.integration.ai.AiModelReleaseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** 管理端分析操作的 Java 边界：确认管理员身份、调用 Python、写入本地审计。 */
@Service
public class AnalysisAdministrationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AnalysisAdministrationService.class);
    private final AiAnalysisClient aiAnalysisClient;
    private final AnalysisSignalDeliveryService analysisSignalDeliveryService;
    private final JdbcClient jdbcClient;

    public AnalysisAdministrationService(
            AiAnalysisClient aiAnalysisClient,
            AnalysisSignalDeliveryService analysisSignalDeliveryService,
            JdbcClient jdbcClient
    ) {
        this.aiAnalysisClient = aiAnalysisClient;
        this.analysisSignalDeliveryService = analysisSignalDeliveryService;
        this.jdbcClient = jdbcClient;
    }

    /** 记录管理员发起动作后排队受控回测；不会同步运行或自动激活发布。 */
    public AiAnalysisRunStatus startRollingBacktest(
            BigDecimal feeRate,
            String benchmarkCode,
            AuthenticatedUser administrator
    ) {
        String auditTarget = benchmarkCode == null ? "STOCK_BASELINE" : "STOCK_BASELINE:" + benchmarkCode;
        writeAudit(administrator, "ANALYSIS_ROLLING_BACKTEST_REQUESTED", auditTarget);
        AiAnalysisRunStatus status = aiAnalysisClient.startRollingBacktest(feeRate, benchmarkCode);
        writeAudit(administrator, "ANALYSIS_ROLLING_BACKTEST_QUEUED", status.analysisRunId().toString());
        LOGGER.info(
                "AnalysisAdministrationService.startRollingBacktest   >>> administratorId={}, analysisRunId={}",
                administrator.userId(), status.analysisRunId()
        );
        return status;
    }

    /** 请求生成已发布评分的 DeepSeek 解释，并仅审计管理员和基金代码。 */
    public AiAnalysisRunStatus startFundExplanation(String fundCode, AuthenticatedUser administrator) {
        writeAudit(administrator, "ANALYSIS_DEEPSEEK_EXPLANATION_REQUESTED", fundCode);
        AiAnalysisRunStatus status = aiAnalysisClient.startFundExplanation(fundCode);
        writeAudit(administrator, "ANALYSIS_DEEPSEEK_EXPLANATION_QUEUED", status.analysisRunId().toString());
        LOGGER.info(
                "AnalysisAdministrationService.startFundExplanation   >>> administratorId={}, fundCode={}, analysisRunId={}",
                administrator.userId(), fundCode, status.analysisRunId()
        );
        return status;
    }

    /** 仅读取候选回测基准摘要；不向浏览器暴露 Python 或来源凭证。 */
    public List<AiBenchmarkSeriesStatus> listStockBenchmarks() {
        return aiAnalysisClient.listStockBenchmarks();
    }

    /** 登记基准元数据并写本地审计；Python 决定来源是否可用。 */
    public AiBenchmarkSeriesStatus registerStockBenchmark(
            String benchmarkCode,
            String displayName,
            String sourceCode,
            String licenseReference,
            AuthenticatedUser administrator
    ) {
        AiBenchmarkSeriesStatus status = aiAnalysisClient.registerStockBenchmark(
                benchmarkCode, displayName, sourceCode, licenseReference
        );
        writeAudit(administrator, "ANALYSIS_BENCHMARK_REGISTERED", benchmarkCode);
        LOGGER.info(
                "AnalysisAdministrationService.registerStockBenchmark   >>> administratorId={}, benchmarkCode={}, status={}",
                administrator.userId(), benchmarkCode, status.status()
        );
        return status;
    }

    /** 批量导入基准点；审计仅记录目标和条数，避免重复存储数据正文。 */
    public AiBenchmarkSeriesStatus importStockBenchmarkPoints(
            String benchmarkCode,
            List<BenchmarkNavPointRequest> points,
            AuthenticatedUser administrator
    ) {
        AiBenchmarkSeriesStatus status = aiAnalysisClient.importStockBenchmarkPoints(
                benchmarkCode,
                points.stream().map(BenchmarkNavPointRequest::navDate).toList(),
                points.stream().map(BenchmarkNavPointRequest::closingValue).toList(),
                points.stream().map(BenchmarkNavPointRequest::sourcePublishedAt).toList()
        );
        writeAudit(administrator, "ANALYSIS_BENCHMARK_POINTS_IMPORTED", benchmarkCode + ":count=" + points.size());
        LOGGER.info(
                "AnalysisAdministrationService.importStockBenchmarkPoints   >>> administratorId={}, benchmarkCode={}, count={}",
                administrator.userId(), benchmarkCode, points.size()
        );
        return status;
    }

    /** 启用已通过来源和覆盖校验的基准；模型仍需单独回测、审核和激活。 */
    public AiBenchmarkSeriesStatus activateStockBenchmark(String benchmarkCode, AuthenticatedUser administrator) {
        AiBenchmarkSeriesStatus status = aiAnalysisClient.activateStockBenchmark(benchmarkCode);
        writeAudit(administrator, "ANALYSIS_BENCHMARK_ACTIVATED", benchmarkCode);
        LOGGER.info(
                "AnalysisAdministrationService.activateStockBenchmark   >>> administratorId={}, benchmarkCode={}",
                administrator.userId(), benchmarkCode
        );
        return status;
    }

    /** 暂停基准，阻止其参与新的回测，保留历史运行与模型审计。 */
    public AiBenchmarkSeriesStatus suspendStockBenchmark(String benchmarkCode, AuthenticatedUser administrator) {
        AiBenchmarkSeriesStatus status = aiAnalysisClient.suspendStockBenchmark(benchmarkCode);
        writeAudit(administrator, "ANALYSIS_BENCHMARK_SUSPENDED", benchmarkCode);
        LOGGER.info(
                "AnalysisAdministrationService.suspendStockBenchmark   >>> administratorId={}, benchmarkCode={}",
                administrator.userId(), benchmarkCode
        );
        return status;
    }

    /** 查询受控运行状态；读取不更新审计内容或任务状态。 */
    public AiAnalysisRunStatus getAnalysisRun(UUID analysisRunId) {
        return aiAnalysisClient.getAnalysisRun(analysisRunId);
    }

    /** 显式激活模型，并记录管理员、理由存在性及目标发布标识。 */
    public AiModelReleaseStatus activateModelRelease(UUID modelReleaseId, String reason, AuthenticatedUser administrator) {
        AiModelReleaseStatus status = aiAnalysisClient.activateModelRelease(modelReleaseId, reason);
        writeAudit(administrator, "ANALYSIS_MODEL_RELEASE_ACTIVATED", modelReleaseId.toString());
        LOGGER.info(
                "AnalysisAdministrationService.activateModelRelease   >>> administratorId={}, modelReleaseId={}",
                administrator.userId(), modelReleaseId
        );
        return status;
    }

    /** 显式暂停模型发布，历史结果和审计记录均保留。 */
    public AiModelReleaseStatus suspendModelRelease(UUID modelReleaseId, String reason, AuthenticatedUser administrator) {
        AiModelReleaseStatus status = aiAnalysisClient.suspendModelRelease(modelReleaseId, reason);
        writeAudit(administrator, "ANALYSIS_MODEL_RELEASE_SUSPENDED", modelReleaseId.toString());
        LOGGER.info(
                "AnalysisAdministrationService.suspendModelRelease   >>> administratorId={}, modelReleaseId={}",
                administrator.userId(), modelReleaseId
        );
        return status;
    }

    /** 管理员可手动消费已发布评分；本操作不创建评分、回测或发布。 */
    public AnalysisSignalDeliveryResponse deliverSignals(AuthenticatedUser administrator) {
        AnalysisSignalDeliveryResponse response = analysisSignalDeliveryService.deliverAvailableSignals();
        writeAudit(administrator, "ANALYSIS_SIGNAL_DELIVERY_EXECUTED", "JAVA_SIGNAL_NOTIFICATION_V1");
        LOGGER.info(
                "AnalysisAdministrationService.deliverSignals   >>> administratorId={}, processed={}, notifications={}",
                administrator.userId(), response.processedCount(), response.notificationCreatedCount()
        );
        return response;
    }

    /** 审计仅记录身份、动作、目标与追踪标识，不写入服务令牌、费率或发布理由正文。 */
    private void writeAudit(AuthenticatedUser administrator, String action, String targetId) {
        jdbcClient.sql("""
                        INSERT INTO audit_log (audit_log_id, trace_id, actor, action, target_id)
                        VALUES (:auditLogId, :traceId, :actor, :action, :targetId)
                        """)
                .param("auditLogId", UUID.randomUUID())
                .param("traceId", TraceContext.getTraceId())
                .param("actor", administrator.userId().toString())
                .param("action", action)
                .param("targetId", targetId)
                .update();
    }
}
