package com.fundradar.core.fund.service;

import com.fundradar.core.fund.api.FundSignalPageResponse;
import com.fundradar.core.fund.api.FundSignalResponse;
import com.fundradar.core.fund.cache.RedisFundReadCache;
import com.fundradar.core.integration.ai.AiServiceUnavailableException;
import com.fundradar.core.integration.ai.AiSignalClient;
import com.fundradar.core.integration.ai.AiSignalPage;
import com.fundradar.core.integration.ai.AiSignalSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * FundSignalQueryService 的 M3 实现。
 *
 * 通过内部客户端读取已持久化评分结果；成功后写入 Redis，AI 服务不可用时仅返回同维度缓存并标记 stale。
 */
@Service
public class InternalFundSignalQueryService implements FundSignalQueryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(InternalFundSignalQueryService.class);

    private final AiSignalClient aiSignalClient;
    private final RedisFundReadCache fundReadCache;

    public InternalFundSignalQueryService(AiSignalClient aiSignalClient, RedisFundReadCache fundReadCache) {
        this.aiSignalClient = aiSignalClient;
        this.fundReadCache = fundReadCache;
    }

    @Override
    /** 查询基金评分结果并执行安全缓存降级；不能根据缓存缺失伪造评分或方向。 */
    public FundSignalPageResponse listSignals(String fundCode, int pageSize, String cursor) {
        try {
            AiSignalPage page = aiSignalClient.listSignals(fundCode, pageSize, cursor);
            FundSignalPageResponse response = new FundSignalPageResponse(
                    page.items().stream().map(this::toResponse).toList(), page.nextCursor(), false, null
            );
            fundReadCache.saveSignalPage(fundCode, pageSize, cursor, response);
            return response;
        } catch (AiServiceUnavailableException exception) {
            return fundReadCache.findSignalPage(fundCode, pageSize, cursor)
                    .map(cached -> {
                        LOGGER.warn(
                                "InternalFundSignalQueryService.listSignals   >>> serving stale signal page from cache, fundCode={}",
                                fundCode
                        );
                        return new FundSignalPageResponse(
                                cached.data().items(), cached.data().nextCursor(), true, cached.cachedAt()
                        );
                    })
                    .orElseThrow(() -> exception);
        }
    }

    /** 将 Python 内部评分结果转换为 Java 对外响应，保留模型与特征版本以保证可追溯。 */
    private FundSignalResponse toResponse(AiSignalSummary signal) {
        return new FundSignalResponse(
                signal.forecastId(),
                signal.asOfDate(),
                signal.scoreStatus(),
                signal.direction(),
                signal.directionalProbability(),
                signal.confidence(),
                signal.riskLevel(),
                signal.maxDrawdownEstimate(),
                signal.explanation(),
                signal.modelVersion(),
                signal.featureVersion(),
                signal.featureCompleteness(),
                signal.scoredAt()
        );
    }
}
