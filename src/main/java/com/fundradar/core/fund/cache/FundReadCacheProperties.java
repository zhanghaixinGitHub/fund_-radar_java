package com.fundradar.core.fund.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Redis 基金读模型缓存配置。
 *
 * 缓存只用于 AI 内部服务短暂不可用时的安全降级，不能作为数据同步、交易或数据源写入机制。
 */
@ConfigurationProperties(prefix = "fund.read-cache")
public class FundReadCacheProperties {

    /** 是否启用读模型缓存与降级读取。 */
    private boolean enabled = true;
    /** 每条成功读模型缓存的存活时间。 */
    private Duration ttl = Duration.ofMinutes(15);

    /** 返回是否启用 Redis 读模型缓存。 */
    public boolean isEnabled() {
        return enabled;
    }

    /** 由配置绑定设置是否启用 Redis 读模型缓存。 */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** 返回读模型缓存的有效期。 */
    public Duration getTtl() {
        return ttl;
    }

    /** 由配置绑定设置读模型缓存的有效期。 */
    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }
}
