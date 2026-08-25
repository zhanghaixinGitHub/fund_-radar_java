package com.fundradar.core.fund.service;

import com.fundradar.core.fund.api.FundEventPageResponse;
import com.fundradar.core.fund.api.FundEventResponse;
import com.fundradar.core.fund.cache.RedisFundReadCache;
import com.fundradar.core.integration.ai.AiEventClient;
import com.fundradar.core.integration.ai.AiEventPage;
import com.fundradar.core.integration.ai.AiEventSummary;
import com.fundradar.core.integration.ai.AiServiceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * FundEventQueryService 的 M2 实现。
 *
 * 只读取 Python 服务已审核事件；成功后写入 Redis，AI 服务短暂不可用时仅回退到同维度缓存。
 */
@Service
public class InternalFundEventQueryService implements FundEventQueryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(InternalFundEventQueryService.class);

    private final AiEventClient aiEventClient;
    private final RedisFundReadCache fundReadCache;

    public InternalFundEventQueryService(AiEventClient aiEventClient, RedisFundReadCache fundReadCache) {
        this.aiEventClient = aiEventClient;
        this.fundReadCache = fundReadCache;
    }

    @Override
    /** 查询基金关联事件并执行安全缓存降级；未命中缓存时继续抛出 AI 服务异常。 */
    public FundEventPageResponse listEvents(String fundCode, int pageSize, String cursor) {
        try {
            AiEventPage page = aiEventClient.listEvents(fundCode, pageSize, cursor);
            FundEventPageResponse response = new FundEventPageResponse(
                    page.items().stream().map(this::toResponse).toList(), page.nextCursor(), false, null
            );
            fundReadCache.saveEventPage(fundCode, pageSize, cursor, response);
            return response;
        } catch (AiServiceUnavailableException exception) {
            return fundReadCache.findEventPage(fundCode, pageSize, cursor)
                    .map(cached -> {
                        LOGGER.warn(
                                "InternalFundEventQueryService.listEvents   >>> serving stale event page from cache, fundCode={}",
                                fundCode
                        );
                        return new FundEventPageResponse(
                                cached.data().items(), cached.data().nextCursor(), true, cached.cachedAt()
                        );
                    })
                    .orElseThrow(() -> exception);
        }
    }

    /** 将内部事件摘要映射为 Java 对外事件卡片，保留来源追溯和关联原因。 */
    private FundEventResponse toResponse(AiEventSummary event) {
        return new FundEventResponse(
                event.eventId(),
                event.eventType(),
                event.summary(),
                event.sourceName(),
                event.sourceUrl(),
                event.publishedAt(),
                event.confidence(),
                event.relevanceScore(),
                event.relationReason()
        );
    }
}
