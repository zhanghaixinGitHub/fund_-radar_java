package com.fundradar.core.config;

import com.fundradar.core.common.web.TraceIdFilter;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Java 对外 Web 层配置。
 *
 * 当前仅配置浏览器到 Java 核心服务的 CORS 白名单；Python AI 内部服务不直接暴露给浏览器。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final WebCorsProperties webCorsProperties;

    public WebConfig(WebCorsProperties webCorsProperties) {
        this.webCorsProperties = webCorsProperties;
    }

    /** 按配置的单一来源地址开放对外 API 所需的方法和请求头。 */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(webCorsProperties.getAllowedOrigin())
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("Content-Type", TraceIdFilter.REQUEST_ID_HEADER)
                .exposedHeaders(TraceIdFilter.TRACE_ID_HEADER);
    }
}
