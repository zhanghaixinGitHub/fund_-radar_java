package com.fundradar.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 为 PostgreSQL JSONB 持久化提供独立的 Jackson 2 映射器，不改变 Web 层消息转换器。 */
@Configuration
public class JsonPersistenceConfig {

    /** 自动注册 Java 时间模块，确保通知和信号载荷可稳定序列化与读取。 */
    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper jsonPersistenceObjectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}
