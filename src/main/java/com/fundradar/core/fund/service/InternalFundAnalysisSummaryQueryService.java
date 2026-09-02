package com.fundradar.core.fund.service;

import com.fundradar.core.fund.api.FundAnalysisSummaryResponse;
import com.fundradar.core.fund.api.FundBacktestSummaryResponse;
import com.fundradar.core.fund.api.FundModelAnalysisSummaryResponse;
import com.fundradar.core.fund.cache.RedisFundReadCache;
import com.fundradar.core.integration.ai.AiBacktestSummary;
import com.fundradar.core.integration.ai.AiFundAnalysisSummary;
import com.fundradar.core.integration.ai.AiModelAnalysisSummary;
import com.fundradar.core.integration.ai.AiServiceUnavailableException;
import com.fundradar.core.integration.ai.AiSignalClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * M3-06 已发布模型与回测摘要的内部读模型实现。
 *
 * <p>仅映射 Python 明确白名单字段；AI 服务故障时只回退同基金的最后成功缓存，并标记为陈旧。</p>
 */
@Service
public class InternalFundAnalysisSummaryQueryService implements FundAnalysisSummaryQueryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(InternalFundAnalysisSummaryQueryService.class);

    private final AiSignalClient aiSignalClient;
    private final RedisFundReadCache fundReadCache;

    public InternalFundAnalysisSummaryQueryService(AiSignalClient aiSignalClient, RedisFundReadCache fundReadCache) {
        this.aiSignalClient = aiSignalClient;
        this.fundReadCache = fundReadCache;
    }

    @Override
    public FundAnalysisSummaryResponse getFundAnalysisSummary(String fundCode) {
        try {
            FundAnalysisSummaryResponse response = toResponse(aiSignalClient.getFundAnalysisSummary(fundCode));
            fundReadCache.saveAnalysisSummary(fundCode, response);
            return response;
        } catch (AiServiceUnavailableException exception) {
            return fundReadCache.findAnalysisSummary(fundCode)
                    .map(cached -> {
                        LOGGER.warn(
                                "InternalFundAnalysisSummaryQueryService.getFundAnalysisSummary   >>> serving stale analysis summary, fundCode={}",
                                fundCode
                        );
                        return withStaleState(cached.data(), cached.cachedAt());
                    })
                    .orElseThrow(() -> exception);
        }
    }

    /** 将内部契约的模型与回测字段显式映射，不让浏览器依赖 Python JSON 命名。 */
    static FundAnalysisSummaryResponse toResponse(AiFundAnalysisSummary source) {
        return new FundAnalysisSummaryResponse(
                source.fundCode(), source.fundType(), source.availabilityStatus(), source.message(),
                toModel(source.model()), toBacktest(source.backtest()), false, null
        );
    }

    private static FundModelAnalysisSummaryResponse toModel(AiModelAnalysisSummary source) {
        if (source == null) {
            return null;
        }
        return new FundModelAnalysisSummaryResponse(
                source.modelReleaseId(), source.modelVersion(), source.featureVersion(), source.releaseStatus(),
                source.effectiveAt(), source.suspendedAt()
        );
    }

    private static FundBacktestSummaryResponse toBacktest(AiBacktestSummary source) {
        if (source == null) {
            return null;
        }
        return new FundBacktestSummaryResponse(
                source.runId(), source.status(), source.publicationStatus(), source.windowStart(), source.windowEnd(),
                source.testStart(), source.testEnd(), source.dataCutoff(), source.feeRate(), source.sampleCount(),
                source.rollingFoldCount(), source.annualizedReturn(), source.maxDrawdown(), source.volatility(),
                source.hitRate(), source.longHoldResult(), source.dcaResult(), source.benchmarkStatus(),
                source.benchmarkResult(), source.completedAt()
        );
    }

    /** 缓存降级时保留已披露的状态与回测事实，仅追加陈旧标识。 */
    private FundAnalysisSummaryResponse withStaleState(FundAnalysisSummaryResponse response, java.time.Instant cachedAt) {
        return new FundAnalysisSummaryResponse(
                response.fundCode(), response.fundType(), response.availabilityStatus(), response.message(),
                response.model(), response.backtest(), true, cachedAt
        );
    }
}
