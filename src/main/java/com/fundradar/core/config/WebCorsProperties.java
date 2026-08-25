package com.fundradar.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 绑定 app.web 配置，限定可以调用 Java 核心服务的浏览器来源。 */
@ConfigurationProperties(prefix = "app.web")
public class WebCorsProperties {

    /** 本地前端开发服务器或部署后前端站点的允许来源。 */
    private String allowedOrigin = "http://localhost:5173";

    /** 读取已配置的 CORS 允许来源。 */
    public String getAllowedOrigin() {
        return allowedOrigin;
    }

    /** 覆盖 CORS 允许来源，由 Spring Boot 配置绑定调用。 */
    public void setAllowedOrigin(String allowedOrigin) {
        this.allowedOrigin = allowedOrigin;
    }
}
