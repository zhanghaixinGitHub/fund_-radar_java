package com.fundradar.core.alert.service;

import com.fundradar.core.alert.api.AlertRuleResponse;
import com.fundradar.core.alert.api.UpsertAlertRuleRequest;
import com.fundradar.core.auth.AuthenticatedUser;
import com.fundradar.core.auth.CurrentUserContext;
import com.fundradar.core.common.trace.TraceContext;
import com.fundradar.core.integration.ai.AiFundClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * AlertRuleService 的 JDBC 实现，按认证上下文隔离每个用户的提醒规则。
 *
 * 规则写入前校验基金存在性，以数据库唯一约束完成幂等更新，并为每次操作写入审计日志。
 */
@Service
public class JdbcAlertRuleService implements AlertRuleService {

    private static final Logger LOGGER = LoggerFactory.getLogger(JdbcAlertRuleService.class);
    private final JdbcClient jdbcClient;
    private final AiFundClient aiFundClient;

    public JdbcAlertRuleService(JdbcClient jdbcClient, AiFundClient aiFundClient) {
        this.jdbcClient = jdbcClient;
        this.aiFundClient = aiFundClient;
    }

    @Override
    /** 查询当前认证用户的提醒规则，不触发任何提醒或外部调用。 */
    public List<AlertRuleResponse> listCurrentUserRules() {
        AuthenticatedUser user = CurrentUserContext.require();
        return jdbcClient.sql("""
                        SELECT rule_id, fund_code, rule_type, threshold, enabled, created_at, updated_at
                        FROM alert_rule
                        WHERE user_id = :userId
                        ORDER BY updated_at DESC, rule_id DESC
                        """)
                .param("userId", user.userId())
                .query((row, rowNumber) -> mapRule(row))
                .list();
    }

    @Override
    @Transactional
    /** 校验基金存在后插入或更新规则，记录审计信息并返回最终持久化状态。 */
    public AlertRuleResponse upsertCurrentUserRule(UpsertAlertRuleRequest request) {
        AuthenticatedUser user = CurrentUserContext.require();
        aiFundClient.getFund(request.fundCode());
        AlertRuleResponse rule = jdbcClient.sql("""
                        INSERT INTO alert_rule (rule_id, user_id, fund_code, rule_type, threshold, enabled)
                        VALUES (:ruleId, :userId, :fundCode, :ruleType, :threshold, :enabled)
                        ON CONFLICT (user_id, fund_code, rule_type)
                        DO UPDATE SET threshold = EXCLUDED.threshold,
                                      enabled = EXCLUDED.enabled,
                                      updated_at = CURRENT_TIMESTAMP
                        RETURNING rule_id, fund_code, rule_type, threshold, enabled, created_at, updated_at
                        """)
                .param("ruleId", UUID.randomUUID())
                .param("userId", user.userId())
                .param("fundCode", request.fundCode())
                .param("ruleType", request.ruleType())
                .param("threshold", request.threshold())
                .param("enabled", request.enabled())
                .query((row, rowNumber) -> mapRule(row))
                .single();
        writeAudit(user, "ALERT_RULE_UPSERT", rule.ruleId().toString());
        LOGGER.info(
                "JdbcAlertRuleService.upsertCurrentUserRule   >>> userId={}, ruleId={}, fundCode={}, ruleType={}, enabled={}",
                user.userId(),
                rule.ruleId(),
                rule.fundCode(),
                rule.ruleType(),
                rule.enabled()
        );
        return rule;
    }

    /** 将 JDBC 查询结果映射为对外提醒规则响应。 */
    private AlertRuleResponse mapRule(java.sql.ResultSet row) throws java.sql.SQLException {
        return new AlertRuleResponse(
                row.getObject("rule_id", UUID.class),
                row.getString("fund_code"),
                row.getString("rule_type"),
                row.getBigDecimal("threshold"),
                row.getBoolean("enabled"),
                row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant()
        );
    }

    /** 将提醒规则变更与当前追踪标识写入审计表，不写入请求中的敏感配置。 */
    private void writeAudit(AuthenticatedUser user, String action, String targetId) {
        jdbcClient.sql("""
                        INSERT INTO audit_log (audit_log_id, trace_id, actor, action, target_id)
                        VALUES (:auditId, :traceId, :actor, :action, :targetId)
                        """)
                .param("auditId", UUID.randomUUID())
                .param("traceId", TraceContext.getTraceId())
                .param("actor", user.userId().toString())
                .param("action", action)
                .param("targetId", targetId)
                .update();
    }
}
