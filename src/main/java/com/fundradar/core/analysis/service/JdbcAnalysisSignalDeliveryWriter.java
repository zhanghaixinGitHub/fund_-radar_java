package com.fundradar.core.analysis.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fundradar.core.integration.ai.AiSignalChange;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 在单个本地事务中写入信号、命中规则、生成通知并最终推进检查点。 */
@Service
class JdbcAnalysisSignalDeliveryWriter {

    static final String CONSUMER_NAME = "JAVA_SIGNAL_NOTIFICATION_V1";
    private static final long DELIVERY_LOCK_KEY = 7_089_123_104L;
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    JdbcAnalysisSignalDeliveryWriter(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    /** 读取当前复合游标，不创建任务或推进状态。 */
    AnalysisDeliveryCheckpoint getCheckpoint() {
        ensureCheckpointExists();
        return jdbcClient.sql("""
                        SELECT last_scored_at, last_forecast_id
                        FROM analysis_delivery_checkpoint
                        WHERE consumer_name = :consumerName
                        """)
                .param("consumerName", CONSUMER_NAME)
                .query((row, rowNumber) -> mapCheckpoint(row))
                .single();
    }

    /** 处理一页远端变更；任一写入失败会回滚整个页面及检查点推进。 */
    @Transactional
    DeliveryPageResult deliverChanges(List<AiSignalChange> changes) {
        if (changes.isEmpty()) {
            return DeliveryPageResult.empty(getCheckpoint());
        }
        if (!tryAcquireDeliveryLock()) {
            throw new AnalysisDeliveryInProgressException();
        }
        ensureCheckpointExists();
        AnalysisDeliveryCheckpoint checkpoint = jdbcClient.sql("""
                        SELECT last_scored_at, last_forecast_id
                        FROM analysis_delivery_checkpoint
                        WHERE consumer_name = :consumerName
                        FOR UPDATE
                        """)
                .param("consumerName", CONSUMER_NAME)
                .query((row, rowNumber) -> mapCheckpoint(row))
                .single();

        List<AiSignalChange> pending = changes.stream()
                .filter(change -> isAfterCheckpoint(change, checkpoint))
                .toList();
        if (pending.isEmpty()) {
            return DeliveryPageResult.empty(checkpoint);
        }

        int signalUpsertedCount = 0;
        int notificationCreatedCount = 0;
        int skippedRuleCount = 0;
        for (AiSignalChange change : pending) {
            StoredSignal storedSignal = upsertSignal(change);
            signalUpsertedCount++;
            for (AlertRuleCandidate rule : findEligibleRules(change.fundCode())) {
                if (!matches(change, storedSignal.previousDirection(), rule)) {
                    skippedRuleCount++;
                    continue;
                }
                if (insertNotification(change, storedSignal.signalId(), rule)) {
                    notificationCreatedCount++;
                    jdbcClient.sql("""
                                    UPDATE alert_rule
                                    SET last_triggered_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                                    WHERE rule_id = :ruleId
                                    """)
                            .param("ruleId", rule.ruleId())
                            .update();
                }
            }
        }
        AiSignalChange last = pending.get(pending.size() - 1);
        jdbcClient.sql("""
                        UPDATE analysis_delivery_checkpoint
                        SET last_scored_at = :lastScoredAt,
                            last_forecast_id = :lastForecastId,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE consumer_name = :consumerName
                        """)
                .param("lastScoredAt", Timestamp.from(last.scoredAt()))
                .param("lastForecastId", last.forecastId())
                .param("consumerName", CONSUMER_NAME)
                .update();
        return new DeliveryPageResult(
                pending.size(), signalUpsertedCount, notificationCreatedCount, skippedRuleCount,
                new AnalysisDeliveryCheckpoint(last.scoredAt(), last.forecastId())
        );
    }

    /** 为首次消费初始化唯一检查点，重复调用保持幂等。 */
    private void ensureCheckpointExists() {
        jdbcClient.sql("""
                        INSERT INTO analysis_delivery_checkpoint (consumer_name)
                        VALUES (:consumerName)
                        ON CONFLICT (consumer_name) DO NOTHING
                        """)
                .param("consumerName", CONSUMER_NAME)
                .update();
    }

    /** 使用 PostgreSQL 事务级咨询锁避免多个 Java 实例并行推进同一消费游标。 */
    private boolean tryAcquireDeliveryLock() {
        Boolean acquired = jdbcClient.sql("SELECT pg_try_advisory_xact_lock(:lockKey)")
                .param("lockKey", DELIVERY_LOCK_KEY)
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(acquired);
    }

    /** 同一时点按 UUID 比较，保证时间相同的评分结果不会遗漏或重复推进。 */
    private boolean isAfterCheckpoint(AiSignalChange change, AnalysisDeliveryCheckpoint checkpoint) {
        if (checkpoint.lastScoredAt() == null) {
            return true;
        }
        int timeOrder = change.scoredAt().compareTo(checkpoint.lastScoredAt());
        return timeOrder > 0 || (timeOrder == 0 && change.forecastId().compareTo(checkpoint.lastForecastId()) > 0);
    }

    /** 先读取更新前方向，再幂等写入本地快照，供 SIGNAL_CHANGE 规则判定。 */
    private StoredSignal upsertSignal(AiSignalChange change) {
        String previousDirection = jdbcClient.sql("""
                        SELECT payload ->> 'direction'
                        FROM signal_snapshot
                        WHERE fund_code = :fundCode
                          AND as_of_date = :asOfDate
                          AND model_version = :modelVersion
                        FOR UPDATE
                        """)
                .param("fundCode", change.fundCode())
                .param("asOfDate", change.asOfDate())
                .param("modelVersion", change.modelVersion())
                .query(String.class)
                .optional()
                .orElse(null);
        String payload = toJson(signalPayload(change));
        String payloadHash = sha256(payload);
        jdbcClient.sql("""
                        INSERT INTO signal_snapshot (
                            signal_id, fund_code, as_of_date, model_version, feature_version, payload_hash, payload, received_at
                        )
                        VALUES (
                            :signalId, :fundCode, :asOfDate, :modelVersion, :featureVersion, :payloadHash,
                            CAST(:payload AS jsonb), CURRENT_TIMESTAMP
                        )
                        ON CONFLICT (fund_code, as_of_date, model_version)
                        DO UPDATE SET feature_version = EXCLUDED.feature_version,
                                      payload_hash = EXCLUDED.payload_hash,
                                      payload = EXCLUDED.payload,
                                      received_at = CURRENT_TIMESTAMP
                        WHERE signal_snapshot.payload_hash IS DISTINCT FROM EXCLUDED.payload_hash
                        """)
                .param("signalId", UUID.randomUUID())
                .param("fundCode", change.fundCode())
                .param("asOfDate", change.asOfDate())
                .param("modelVersion", change.modelVersion())
                .param("featureVersion", change.featureVersion())
                .param("payloadHash", payloadHash)
                .param("payload", payload)
                .update();
        UUID signalId = jdbcClient.sql("""
                        SELECT signal_id
                        FROM signal_snapshot
                        WHERE fund_code = :fundCode
                          AND as_of_date = :asOfDate
                          AND model_version = :modelVersion
                        """)
                .param("fundCode", change.fundCode())
                .param("asOfDate", change.asOfDate())
                .param("modelVersion", change.modelVersion())
                .query(UUID.class)
                .single();
        return new StoredSignal(signalId, previousDirection);
    }

    /** 只读取关注该基金、启用且已过冷却期的规则；规则行在当前事务中锁定。 */
    private List<AlertRuleCandidate> findEligibleRules(String fundCode) {
        return jdbcClient.sql("""
                        SELECT rule.rule_id, rule.rule_type, rule.threshold
                        FROM alert_rule rule
                        WHERE rule.fund_code = :fundCode
                          AND rule.enabled = TRUE
                          AND EXISTS (
                              SELECT 1
                              FROM watchlist_item watchlist
                              WHERE watchlist.user_id = rule.user_id
                                AND watchlist.fund_code = rule.fund_code
                          )
                          AND (
                              rule.last_triggered_at IS NULL
                              OR rule.last_triggered_at <= CURRENT_TIMESTAMP - rule.cooldown_hours * INTERVAL '1 hour'
                          )
                        FOR UPDATE OF rule
                        """)
                .param("fundCode", fundCode)
                .query((row, rowNumber) -> new AlertRuleCandidate(
                        row.getObject("rule_id", UUID.class),
                        row.getString("rule_type"),
                        row.getBigDecimal("threshold")
                ))
                .list();
    }

    /** 按规则语义判断是否提醒：风险阈值、方向变化和事件规则互不混淆。 */
    private boolean matches(AiSignalChange change, String previousDirection, AlertRuleCandidate rule) {
        return switch (rule.ruleType()) {
            case "RISK_LEVEL" -> riskScore(change.riskLevel()).compareTo(rule.threshold()) >= 0;
            case "SIGNAL_CHANGE" -> previousDirection != null && !previousDirection.equals(change.direction());
            case "EVENT" -> false;
            default -> false;
        };
    }

    /** 以稳定、可解释的等级映射对齐现有 0 至 1 的风险阈值规则。 */
    private BigDecimal riskScore(String riskLevel) {
        return switch (riskLevel) {
            case "HIGH" -> BigDecimal.ONE;
            case "MEDIUM" -> new BigDecimal("0.5000");
            case "LOW" -> new BigDecimal("0.2500");
            default -> BigDecimal.ZERO;
        };
    }

    /** 使用 forecast + rule 作为幂等键；同一结果重放不会重复写通知或刷新冷却期。 */
    private boolean insertNotification(AiSignalChange change, UUID signalId, AlertRuleCandidate rule) {
        String triggerType = "RISK_LEVEL".equals(rule.ruleType()) ? "RISK" : "SIGNAL";
        int inserted = jdbcClient.sql("""
                        INSERT INTO notification (
                            notification_id, rule_id, deduplication_key, status, payload, trigger_type, trigger_ref, signal_id
                        )
                        VALUES (
                            :notificationId, :ruleId, :deduplicationKey, 'UNREAD', CAST(:payload AS jsonb),
                            :triggerType, :triggerRef, :signalId
                        )
                        ON CONFLICT (deduplication_key) DO NOTHING
                        """)
                .param("notificationId", UUID.randomUUID())
                .param("ruleId", rule.ruleId())
                .param("deduplicationKey", "SIGNAL:" + rule.ruleId() + ":" + change.forecastId())
                .param("payload", toJson(notificationPayload(change, rule.ruleType())))
                .param("triggerType", triggerType)
                .param("triggerRef", change.forecastId().toString())
                .param("signalId", signalId)
                .update();
        return inserted == 1;
    }

    /** 构造本地信号快照，不保存 Python 运行详情、令牌或外部原始数据。 */
    private Map<String, Object> signalPayload(AiSignalChange change) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("forecastId", change.forecastId().toString());
        payload.put("modelReleaseId", change.modelReleaseId().toString());
        payload.put("direction", change.direction());
        payload.put("directionalProbability", change.directionalProbability());
        payload.put("confidence", change.confidence());
        payload.put("riskLevel", change.riskLevel());
        payload.put("maxDrawdownEstimate", change.maxDrawdownEstimate());
        payload.put("explanation", change.explanation());
        payload.put("featureCompleteness", change.featureCompleteness());
        payload.put("scoredAt", change.scoredAt().toString());
        return payload;
    }

    /** 构造用户可见的提醒载荷；它是信息提示，并明确保留数据截至时间。 */
    private Map<String, Object> notificationPayload(AiSignalChange change, String ruleType) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fundCode", change.fundCode());
        payload.put("asOfDate", change.asOfDate().toString());
        payload.put("ruleType", ruleType);
        payload.put("direction", change.direction());
        payload.put("riskLevel", change.riskLevel());
        payload.put("confidence", change.confidence());
        payload.put("explanation", change.explanation());
        payload.put("scoredAt", change.scoredAt().toString());
        payload.put("notice", "仅供信息参考，不构成投资建议或交易指令。");
        return payload;
    }

    /** JSON 序列化失败属于本地事务错误，必须阻止检查点推进。 */
    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("analysis payload serialization failed", exception);
        }
    }

    /** 生成 SHA-256 摘要作为本地快照的可复现去重依据。 */
    private String sha256(String payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private AnalysisDeliveryCheckpoint mapCheckpoint(ResultSet row) throws SQLException {
        Timestamp scoredAt = row.getTimestamp("last_scored_at");
        return new AnalysisDeliveryCheckpoint(
                scoredAt == null ? null : scoredAt.toInstant(),
                row.getObject("last_forecast_id", UUID.class)
        );
    }

    private record StoredSignal(UUID signalId, String previousDirection) {
    }

    private record AlertRuleCandidate(UUID ruleId, String ruleType, BigDecimal threshold) {
    }

    record DeliveryPageResult(
            int processedCount,
            int signalUpsertedCount,
            int notificationCreatedCount,
            int skippedRuleCount,
            AnalysisDeliveryCheckpoint checkpoint
    ) {
        static DeliveryPageResult empty(AnalysisDeliveryCheckpoint checkpoint) {
            return new DeliveryPageResult(0, 0, 0, 0, checkpoint);
        }
    }
}
