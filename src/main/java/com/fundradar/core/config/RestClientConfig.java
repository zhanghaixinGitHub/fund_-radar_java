package com.fundradar.core.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** 为内部服务调用提供 RestClient 构造器，不依赖特定的 Web Starter 实现。 */
@Configuration
public class RestClientConfig {

    @Bean
    @ConditionalOnMissingBean(RestClient.Builder.class)
    /** 返回可被 AI 客户端复用的基础 RestClient 构造器。 */
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }
}
