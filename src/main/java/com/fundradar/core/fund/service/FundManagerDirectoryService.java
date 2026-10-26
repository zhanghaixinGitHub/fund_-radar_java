package com.fundradar.core.fund.service;

import com.fundradar.core.fund.api.FundManagerDirectory;
import com.fundradar.core.fund.cache.FundReadCacheProperties;
import com.fundradar.core.integration.ai.AiFundManagerDirectoryClient;
import com.fundradar.core.integration.ai.AiServiceUnavailableException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** 公共经理读模型协调：共享缓存没有任何个人关注、持仓或权限结论。 */
@Service
public class FundManagerDirectoryService {
    private static final Logger LOG = LoggerFactory.getLogger(FundManagerDirectoryService.class);
    private final AiFundManagerDirectoryClient client;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final FundReadCacheProperties properties;
    public FundManagerDirectoryService(AiFundManagerDirectoryClient client, StringRedisTemplate redis,
            ObjectMapper mapper, FundReadCacheProperties properties) {
        this.client = client; this.redis = redis; this.mapper = mapper; this.properties = properties;
    }
    public FundManagerDirectory.Page list(String keyword, String company, String type, String state,
            int page, int size, UUID snapshot) {
        String key = "list:" + keyword + "\u0000" + company + "\u0000" + type + ":" + state + ":" + page + ":" + size + ":" + snapshot;
        return read(key, FundManagerDirectory.Page.class, () -> client.list(keyword, company, type, state, page, size, snapshot));
    }
    public FundManagerDirectory.Detail detail(UUID id, UUID snapshot) {
        return read("detail:" + id + ":" + snapshot, FundManagerDirectory.Detail.class, () -> client.detail(id, snapshot));
    }
    public java.util.Map<String, String> links(String fundCode) { return client.links(fundCode); }
    public FundManagerDirectory.FundPage funds(UUID id, String state, int page, int size, UUID snapshot) {
        return client.funds(id, state, page, size, snapshot);
    }
    public FundManagerDirectory.RelatedDetail related(UUID id, UUID fundId, UUID snapshot) {
        return client.related(id, fundId, snapshot);
    }
    public FundManagerDirectory.Fund performance(UUID id, UUID fundId, UUID tenureId, UUID snapshot) {
        return client.performance(id, fundId, tenureId, snapshot);
    }
    private <T> T read(String key, Class<T> type, Supplier<T> source) {
        String cacheKey = "fund-radar:v1:manager-public:" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(key.getBytes(StandardCharsets.UTF_8));
        try {
            T value = source.get();
            if (properties.isEnabled()) {
                try { redis.opsForValue().set(cacheKey, mapper.writeValueAsString(value), properties.getTtl()); }
                catch (RuntimeException error) { LOG.warn("FundManagerDirectoryService.read >>> public cache write failed", error); }
            }
            return value;
        } catch (AiServiceUnavailableException unavailable) {
            if (properties.isEnabled()) {
                try {
                    String json = redis.opsForValue().get(cacheKey);
                    if (json != null) {
                        T value = mapper.readValue(json, type);
                        if (value instanceof FundManagerDirectory.Detail detail) return type.cast(detail.asStale());
                        if (value instanceof FundManagerDirectory.Page page) return type.cast(page.asStale());
                    }
                } catch (RuntimeException error) { LOG.warn("FundManagerDirectoryService.read >>> public cache read failed", error); }
            }
            throw unavailable;
        }
    }
}
