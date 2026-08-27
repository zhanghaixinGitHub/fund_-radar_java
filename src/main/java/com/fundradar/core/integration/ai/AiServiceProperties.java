package com.fundradar.core.integration.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 绑定 ai.service 配置的内部服务调用参数。
 *
 * 服务令牌只从环境变量或本地受忽略配置读取，禁止写入日志、响应或源代码。
 */
@ConfigurationProperties(prefix = "ai.service")
public class AiServiceProperties {

    /** Python AI 内部服务的基础地址。 */
    private String baseUrl = "http://localhost:8000";
    /** Java 调用 Python 内部接口时使用的服务令牌。 */
    private String token = "";
    /** 建立 TCP 连接的最长等待时间。 */
    private Duration connectTimeout = Duration.ofSeconds(2);
    /** 等待 Python 返回读模型的最长时间。 */
    private Duration readTimeout = Duration.ofSeconds(3);
    /** 等待用户主动发起净值同步完成的最长时间；不影响普通读模型请求。 */
    private Duration manualSyncReadTimeout = Duration.ofMinutes(5);

    /** 返回 Python AI 内部服务基础地址。 */
    public String getBaseUrl() {
        return baseUrl;
    }

    /** 由配置绑定设置 Python AI 内部服务基础地址。 */
    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /** 返回服务间认证令牌；调用方不得写入日志或响应。 */
    public String getToken() {
        return token;
    }

    /** 由受保护的配置源绑定服务间认证令牌。 */
    public void setToken(String token) {
        this.token = token;
    }

    /** 返回内部 HTTP 连接超时配置。 */
    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    /** 由配置绑定设置内部 HTTP 连接超时。 */
    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    /** 返回内部 HTTP 读取超时配置。 */
    public Duration getReadTimeout() {
        return readTimeout;
    }

    /** 由配置绑定设置内部 HTTP 读取超时。 */
    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    /** 返回人工净值同步调用的最长读取等待时间。 */
    public Duration getManualSyncReadTimeout() {
        return manualSyncReadTimeout;
    }

    /** 由受保护的配置源绑定人工净值同步读取超时。 */
    public void setManualSyncReadTimeout(Duration manualSyncReadTimeout) {
        this.manualSyncReadTimeout = manualSyncReadTimeout;
    }
}
