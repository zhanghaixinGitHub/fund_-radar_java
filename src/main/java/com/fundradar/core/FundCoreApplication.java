package com.fundradar.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 基金雷达 Java 核心服务的启动入口。
 *
 * 负责装配对外 HTTP 接口、数据库迁移、Redis 读缓存与对 Python AI 内部服务的受控访问。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class FundCoreApplication {

    /** 启动 Spring Boot 应用。 */
    public static void main(String[] args) {
        SpringApplication.run(FundCoreApplication.class, args);
    }
}
