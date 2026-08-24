package com.fundradar.core.config;

import com.fundradar.core.common.web.TraceIdFilter;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final WebCorsProperties webCorsProperties;

    public WebConfig(WebCorsProperties webCorsProperties) {
        this.webCorsProperties = webCorsProperties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(webCorsProperties.getAllowedOrigin())
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("Content-Type", TraceIdFilter.REQUEST_ID_HEADER)
                .exposedHeaders(TraceIdFilter.TRACE_ID_HEADER);
    }
}
