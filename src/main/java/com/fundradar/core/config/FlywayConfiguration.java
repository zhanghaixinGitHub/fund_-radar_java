package com.fundradar.core.config;

import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * 在 Web 接口接受流量前执行 Java 核心服务自有的数据库迁移。
 *
 * <p>关联文档：docs_zhx/requirements/fund-radar.md；
 * docs_zhx/design/fund-radar.md；docs_zhx/testcase/fund-radar.md。</p>
 */
@Configuration
public class FlywayConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "spring.flyway", name = "enabled", havingValue = "true", matchIfMissing = true)
    /** 创建 Flyway 实例，管理 classpath:db/migration 下的 Java 核心服务结构版本。 */
    public Flyway migrateFundCoreSchema(DataSource dataSource) {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();
        return flyway;
    }
}
