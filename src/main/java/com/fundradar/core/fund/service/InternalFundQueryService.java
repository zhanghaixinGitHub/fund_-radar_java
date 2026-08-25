package com.fundradar.core.fund.service;

import com.fundradar.core.fund.api.FundDetailResponse;
import com.fundradar.core.fund.api.FundPageResponse;
import com.fundradar.core.fund.api.FundSummaryResponse;
import com.fundradar.core.integration.ai.AiFundClient;
import com.fundradar.core.integration.ai.AiFundDetail;
import com.fundradar.core.integration.ai.AiFundPage;
import com.fundradar.core.integration.ai.AiFundSummary;
import com.fundradar.core.integration.ai.AiServiceUnavailableException;
import com.fundradar.core.fund.cache.RedisFundReadCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * FundQueryService 的 M0 实现。
 *
 * 正常情况下通过内部客户端读取 Python 的基金读模型并写入 Redis；仅在 AI 服务不可用时读取同维度缓存，并显式标记 stale。
 */
@Service
public class InternalFundQueryService implements FundQueryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(InternalFundQueryService.class);

    private final AiFundClient aiFundClient;
    private final RedisFundReadCache fundReadCache;

    public InternalFundQueryService(AiFundClient aiFundClient, RedisFundReadCache fundReadCache) {
        this.aiFundClient = aiFundClient;
        this.fundReadCache = fundReadCache;
    }

    @Override
    /** 查询基金分页；无缓存的 AI 服务异常会继续抛出，不能伪造列表结果。 */
    public FundPageResponse listFunds(String keyword, int pageSize, String cursor) {
        try {
            AiFundPage page = aiFundClient.listFunds(keyword, pageSize, cursor);
            FundPageResponse response = new FundPageResponse(
                    page.items().stream().map(this::toSummaryResponse).toList(),
                    page.nextCursor(),
                    false,
                    null
            );
            fundReadCache.savePage(keyword, pageSize, cursor, response);
            return response;
        } catch (AiServiceUnavailableException exception) {
            return fundReadCache.findPage(keyword, pageSize, cursor)
                    .map(cached -> {
                        LOGGER.warn("InternalFundQueryService.listFunds   >>> serving stale fund page from cache");
                        return new FundPageResponse(
                                cached.data().items(), cached.data().nextCursor(), true, cached.cachedAt()
                        );
                    })
                    .orElseThrow(() -> exception);
        }
    }

    @Override
    /** 查询基金详情；无缓存的 AI 服务异常会继续抛出，基金不存在异常不参与缓存降级。 */
    public FundDetailResponse getFund(String fundCode) {
        try {
            AiFundDetail fund = aiFundClient.getFund(fundCode);
            FundDetailResponse response = toDetailResponse(fund);
            fundReadCache.saveDetail(fundCode, response);
            return response;
        } catch (AiServiceUnavailableException exception) {
            return fundReadCache.findDetail(fundCode)
                    .map(cached -> {
                        LOGGER.warn("InternalFundQueryService.getFund   >>> serving stale fund detail from cache, fundCode={}", fundCode);
                        FundDetailResponse data = cached.data();
                        return new FundDetailResponse(
                                data.fundCode(), data.fundName(), data.fundType(), data.status(), data.asOfDate(),
                                data.navStatus(), data.dataSource(), true, cached.cachedAt()
                        );
                    })
                    .orElseThrow(() -> exception);
        }
    }

    /** 将 Python 内部详情转换为 Java 对外详情，并标记为实时结果。 */
    private FundDetailResponse toDetailResponse(AiFundDetail fund) {
        return new FundDetailResponse(
                fund.fundCode(),
                fund.fundName(),
                fund.fundType(),
                fund.status(),
                fund.asOfDate(),
                fund.navStatus(),
                fund.dataSource(),
                false,
                null
        );
    }

    /** 将 Python 内部摘要转换为 Java 对外列表项。 */
    private FundSummaryResponse toSummaryResponse(AiFundSummary fund) {
        return new FundSummaryResponse(
                fund.fundCode(),
                fund.fundName(),
                fund.fundType(),
                fund.status(),
                fund.asOfDate()
        );
    }
}
