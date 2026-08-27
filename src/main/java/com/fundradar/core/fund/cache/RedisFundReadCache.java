package com.fundradar.core.fund.cache;

import com.fundradar.core.fund.api.FundDetailResponse;
import com.fundradar.core.fund.api.FundEventPageResponse;
import com.fundradar.core.fund.api.FundNavHistoryResponse;
import com.fundradar.core.fund.api.FundPageResponse;
import com.fundradar.core.fund.api.FundSignalPageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Optional;

import tools.jackson.databind.ObjectMapper;

/**
 * 保存 Java 最后一次成功读取的读模型，以便 Python AI 服务短暂不可用时进行可披露的安全降级。
 *
 * <p>关联文档：docs_zhx/requirements/fund-radar.md；
 * docs_zhx/design/fund-radar.md；docs_zhx/testcase/fund-radar.md。</p>
 */
@Service
public class RedisFundReadCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisFundReadCache.class);
    private static final String KEY_PREFIX = "fund-radar:v1:read-model:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final FundReadCacheProperties properties;

    public RedisFundReadCache(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            FundReadCacheProperties properties
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /** 缓存一次成功的基金列表响应，并记录缓存生成时间。 */
    public void savePage(String keyword, int pageSize, String cursor, Integer page, FundPageResponse response) {
        save(pageKey(keyword, pageSize, cursor, page), new CachedPage(response, Instant.now()));
    }

    /** 按完整查询维度读取基金列表缓存；未命中或缓存故障时返回空。 */
    public Optional<CachedPage> findPage(String keyword, int pageSize, String cursor, Integer page) {
        return find(pageKey(keyword, pageSize, cursor, page), CachedPage.class);
    }

    /** 缓存一次成功的基金详情响应。 */
    public void saveDetail(String fundCode, FundDetailResponse detail) {
        save(detailKey(fundCode), new CachedDetail(detail, Instant.now()));
    }

    /** 读取指定基金详情的最后一次成功缓存。 */
    public Optional<CachedDetail> findDetail(String fundCode) {
        return find(detailKey(fundCode), CachedDetail.class);
    }

    /** 缓存一次成功的基金历史净值窗口。 */
    public void saveNavHistory(
            String fundCode, LocalDate startDate, LocalDate endDate, FundNavHistoryResponse history
    ) {
        save(navHistoryKey(fundCode, startDate, endDate), new CachedNavHistory(history, Instant.now()));
    }

    /** 读取指定基金和日期窗口的最后一次成功历史净值缓存。 */
    public Optional<CachedNavHistory> findNavHistory(String fundCode, LocalDate startDate, LocalDate endDate) {
        return find(navHistoryKey(fundCode, startDate, endDate), CachedNavHistory.class);
    }

    /** 缓存一次成功的关联事件分页响应。 */
    public void saveEventPage(String fundCode, int pageSize, String cursor, FundEventPageResponse page) {
        save(eventPageKey(fundCode, pageSize, cursor), new CachedEventPage(page, Instant.now()));
    }

    /** 按基金、页大小和游标读取关联事件分页缓存。 */
    public Optional<CachedEventPage> findEventPage(String fundCode, int pageSize, String cursor) {
        return find(eventPageKey(fundCode, pageSize, cursor), CachedEventPage.class);
    }

    /** 缓存一次成功的 M3 评分结果分页响应。 */
    public void saveSignalPage(String fundCode, int pageSize, String cursor, FundSignalPageResponse page) {
        save(signalPageKey(fundCode, pageSize, cursor), new CachedSignalPage(page, Instant.now()));
    }

    /** 按基金、页大小和游标读取 M3 评分结果分页缓存。 */
    public Optional<CachedSignalPage> findSignalPage(String fundCode, int pageSize, String cursor) {
        return find(signalPageKey(fundCode, pageSize, cursor), CachedSignalPage.class);
    }

    /** 将对象序列化到 Redis；缓存故障仅记录告警，不影响主查询成功结果。 */
    private void save(String key, Object value) {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(value), properties.getTtl());
        } catch (RuntimeException exception) {
            LOGGER.warn("RedisFundReadCache.save   >>> unable to persist read-model cache, key={}", key, exception);
        }
    }

    /** 从 Redis 反序列化缓存；禁用、未命中或反序列化失败时统一按空缓存处理。 */
    private <T> Optional<T> find(String key, Class<T> valueType) {
        if (!properties.isEnabled()) {
            return Optional.empty();
        }
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, valueType));
        } catch (RuntimeException exception) {
            LOGGER.warn("RedisFundReadCache.find   >>> unable to load read-model cache, key={}", key, exception);
            return Optional.empty();
        }
    }

    /** 生成基金列表缓存键，关键字、游标和页码均隔离，避免不同分页模式串页。 */
    private String pageKey(String keyword, int pageSize, String cursor, Integer page) {
        return KEY_PREFIX + "page:k=" + encode(normalize(keyword)) + ":s=" + pageSize
                + ":c=" + encode(normalize(cursor)) + ":p=" + (page == null ? "" : page);
    }

    /** 生成按基金代码隔离的详情缓存键。 */
    private String detailKey(String fundCode) {
        return KEY_PREFIX + "detail:" + fundCode;
    }

    /** 生成基金与日期窗口共同隔离的历史净值缓存键。 */
    private String navHistoryKey(String fundCode, LocalDate startDate, LocalDate endDate) {
        return KEY_PREFIX + "nav-history:f=" + fundCode + ":s=" + startDate + ":e=" + endDate;
    }

    /** 生成关联事件分页缓存键。 */
    private String eventPageKey(String fundCode, int pageSize, String cursor) {
        return KEY_PREFIX + "events:f=" + fundCode + ":s=" + pageSize + ":c=" + encode(normalize(cursor));
    }

    /** 生成 M3 评分结果分页缓存键。 */
    private String signalPageKey(String fundCode, int pageSize, String cursor) {
        return KEY_PREFIX + "signals:f=" + fundCode + ":s=" + pageSize + ":c=" + encode(normalize(cursor));
    }

    /** 将 null 统一为空字符串并去除首尾空白，确保同义查询使用同一缓存键。 */
    private String normalize(String value) {
        return value == null ? "" : value.strip();
    }

    /** 使用 URL 安全 Base64 编码查询片段，避免原始参数破坏 Redis 键结构。 */
    private String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /** 基金列表缓存值及其生成时间。 */
    public record CachedPage(FundPageResponse data, Instant cachedAt) {
    }

    /** 基金详情缓存值及其生成时间。 */
    public record CachedDetail(FundDetailResponse data, Instant cachedAt) {
    }

    /** 基金历史净值缓存值及其生成时间。 */
    public record CachedNavHistory(FundNavHistoryResponse data, Instant cachedAt) {
    }

    /** 关联事件分页缓存值及其生成时间。 */
    public record CachedEventPage(FundEventPageResponse data, Instant cachedAt) {
    }

    /** M3 评分结果分页缓存值及其生成时间。 */
    public record CachedSignalPage(FundSignalPageResponse data, Instant cachedAt) {
    }
}
