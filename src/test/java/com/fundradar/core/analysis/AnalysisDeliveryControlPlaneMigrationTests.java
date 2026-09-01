package com.fundradar.core.analysis;

import com.fundradar.core.FundCoreApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 验证 M3 投递控制面迁移已建立结构，但不启动评分消费或通知写入。 */
@SpringBootTest(classes = FundCoreApplication.class)
class AnalysisDeliveryControlPlaneMigrationTests {

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void exposesDeliveryCheckpointAndNotificationTraceabilityColumns() {
        assertEquals(1, jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM information_schema.tables
                        WHERE table_schema = current_schema()
                          AND table_name = 'analysis_delivery_checkpoint'
                        """)
                .query(Integer.class)
                .single());
        assertEquals(2, jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM information_schema.columns
                        WHERE table_schema = current_schema()
                          AND table_name = 'alert_rule'
                          AND column_name IN ('cooldown_hours', 'last_triggered_at')
                        """)
                .query(Integer.class)
                .single());
        assertEquals(3, jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM information_schema.columns
                        WHERE table_schema = current_schema()
                          AND table_name = 'notification'
                          AND column_name IN ('trigger_type', 'trigger_ref', 'signal_id')
                        """)
                .query(Integer.class)
                .single());
        assertEquals(2, jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM system_permission
                        WHERE permission_code IN ('NOTIFICATION_SELF_READ', 'NOTIFICATION_SELF_WRITE')
                        """)
                .query(Integer.class)
                .single());
    }
}
