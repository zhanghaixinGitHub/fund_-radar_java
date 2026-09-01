package com.fundradar.core.analysis.service;

import com.fundradar.core.integration.ai.AiSignalChange;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 验证一页评分在本地事务中完成通知去重与复合检查点推进。 */
@SpringBootTest
@Transactional
class AnalysisSignalDeliveryWriterIntegrationTests {

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private JdbcAnalysisSignalDeliveryWriter deliveryWriter;

    @Test
    void createsOneRiskNotificationAndDoesNotDuplicateWhenTheSameSignalIsReplayed() {
        jdbcClient.sql("DELETE FROM analysis_delivery_checkpoint WHERE consumer_name = :consumerName")
                .param("consumerName", JdbcAnalysisSignalDeliveryWriter.CONSUMER_NAME)
                .update();
        UUID userId = UUID.randomUUID();
        String fundCode = "TEST" + userId.toString().substring(0, 6);
        UUID ruleId = UUID.randomUUID();
        createUserAndFollowedRiskRule(userId, fundCode, ruleId);
        AiSignalChange change = new AiSignalChange(
                UUID.randomUUID(),
                fundCode,
                LocalDate.of(2026, 9, 1),
                "baseline-v1",
                "feature-v1",
                UUID.randomUUID(),
                "UP",
                new BigDecimal("0.6000"),
                new BigDecimal("0.5500"),
                "HIGH",
                new BigDecimal("0.120000"),
                "仅供信息参考。",
                new BigDecimal("0.9000"),
                Instant.parse("2026-09-01T00:00:00Z")
        );

        JdbcAnalysisSignalDeliveryWriter.DeliveryPageResult first = deliveryWriter.deliverChanges(List.of(change));
        JdbcAnalysisSignalDeliveryWriter.DeliveryPageResult replay = deliveryWriter.deliverChanges(List.of(change));

        assertEquals(1, first.processedCount());
        assertEquals(1, first.notificationCreatedCount());
        assertEquals(0, replay.processedCount());
        assertEquals(1, jdbcClient.sql("SELECT COUNT(*) FROM notification WHERE rule_id = :ruleId")
                .param("ruleId", ruleId)
                .query(Integer.class)
                .single());
        assertEquals(change.forecastId(), deliveryWriter.getCheckpoint().lastForecastId());
        assertEquals(change.scoredAt(), deliveryWriter.getCheckpoint().lastScoredAt());
    }

    /** 构造本地独立账户、关注记录和风险规则；测试事务结束后自动回滚。 */
    private void createUserAndFollowedRiskRule(UUID userId, String fundCode, UUID ruleId) {
        jdbcClient.sql("""
                        INSERT INTO user_account (user_id, mobile, display_name, password_hash, role, status)
                        VALUES (:userId, :mobile, :displayName, :passwordHash, 'FUND_USER', 'ACTIVE')
                        """)
                .param("userId", userId)
                .param("mobile", "139" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000)))
                .param("displayName", "分析投递测试账户")
                .param("passwordHash", "not-a-real-password-hash")
                .update();
        jdbcClient.sql("""
                        INSERT INTO watchlist_item (watchlist_item_id, user_id, fund_code, fund_type)
                        VALUES (:watchlistItemId, :userId, :fundCode, 'STOCK')
                        """)
                .param("watchlistItemId", UUID.randomUUID())
                .param("userId", userId)
                .param("fundCode", fundCode)
                .update();
        jdbcClient.sql("""
                        INSERT INTO alert_rule (rule_id, user_id, fund_code, rule_type, threshold, enabled)
                        VALUES (:ruleId, :userId, :fundCode, 'RISK_LEVEL', 0.5000, TRUE)
                        """)
                .param("ruleId", ruleId)
                .param("userId", userId)
                .param("fundCode", fundCode)
                .update();
    }
}
