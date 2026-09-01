package com.fundradar.core.fund.service;

import com.fundradar.core.fund.api.FundFeatureMetricsResponse;
import com.fundradar.core.fund.api.FundFeatureStatusResponse;
import com.fundradar.core.fund.cache.RedisFundReadCache;
import com.fundradar.core.integration.ai.AiFeatureClient;
import com.fundradar.core.integration.ai.AiFeatureSnapshot;
import com.fundradar.core.integration.ai.AiFeatureStatus;
import com.fundradar.core.integration.ai.AiServiceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;

/**
 * FundFeatureStatusQueryService 的 M3-G1 实现。
 *
 * <p>只转发已持久化的历史统计特征；AI 服务不可用时仅返回同基金缓存并标记 stale。</p>
 */
@Service
public class InternalFundFeatureStatusQueryService implements FundFeatureStatusQueryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(InternalFundFeatureStatusQueryService.class);

    private final AiFeatureClient aiFeatureClient;
    private final RedisFundReadCache fundReadCache;

    public InternalFundFeatureStatusQueryService(AiFeatureClient aiFeatureClient, RedisFundReadCache fundReadCache) {
        this.aiFeatureClient = aiFeatureClient;
        this.fundReadCache = fundReadCache;
    }

    @Override
    public FundFeatureStatusResponse getLatestFeatureStatus(String fundCode) {
        try {
            AiFeatureStatus status = aiFeatureClient.getLatestFeatureStatus(fundCode);
            FundFeatureStatusResponse response = toResponse(status);
            fundReadCache.saveFeatureStatus(fundCode, response);
            return response;
        } catch (AiServiceUnavailableException exception) {
            return fundReadCache.findFeatureStatus(fundCode)
                    .map(cached -> {
                        LOGGER.warn(
                                "InternalFundFeatureStatusQueryService.getLatestFeatureStatus   >>> serving stale feature status from cache, fundCode={}",
                                fundCode
                        );
                        return withStaleState(cached.data(), cached.cachedAt());
                    })
                    .orElseThrow(() -> exception);
        }
    }

    /** 将 Python 内部契约映射为浏览器可读的固定统计字段，拒绝透传任意 JSON。 */
    private FundFeatureStatusResponse toResponse(AiFeatureStatus status) {
        if (status.snapshot() == null) {
            return unavailable(status.status(), false, null);
        }
        AiFeatureSnapshot snapshot = status.snapshot();
        return new FundFeatureStatusResponse(
                status.status(), snapshot.asOfDate(), snapshot.fundType(), snapshot.featureVersion(),
                snapshot.completeness(), snapshot.eligibilityStatus(), snapshot.unavailableReason(),
                snapshot.sourceCode(), snapshot.sourceSyncFinishedAt(), snapshot.navValueBasis(),
                toMetrics(snapshot.metrics()), snapshot.computedAt(), false, null
        );
    }

    /** 将可选指标映射为稳定字段名；数据不足时保持 null，不隐式填零。 */
    private FundFeatureMetricsResponse toMetrics(Map<String, BigDecimal> metrics) {
        if (metrics == null) {
            return null;
        }
        return new FundFeatureMetricsResponse(
                metrics.get("return_5d"),
                metrics.get("return_20d"),
                metrics.get("return_60d"),
                metrics.get("volatility_20d"),
                metrics.get("max_drawdown_60d")
        );
    }

    /** 缓存降级时保留原始状态和字段，仅标识其缓存时间。 */
    private FundFeatureStatusResponse withStaleState(FundFeatureStatusResponse response, java.time.Instant cachedAt) {
        return new FundFeatureStatusResponse(
                response.status(), response.asOfDate(), response.fundType(), response.featureVersion(),
                response.completeness(), response.eligibilityStatus(), response.unavailableReason(), response.sourceCode(),
                response.sourceSyncFinishedAt(), response.navValueBasis(), response.metrics(), response.computedAt(), true, cachedAt
        );
    }

    /** 没有已落库快照时，除状态和缓存字段外一律为空。 */
    private FundFeatureStatusResponse unavailable(String status, boolean stale, java.time.Instant cachedAt) {
        return new FundFeatureStatusResponse(
                status, null, null, null, null, null, null, null, null, null, null, null, stale, cachedAt
        );
    }
}
