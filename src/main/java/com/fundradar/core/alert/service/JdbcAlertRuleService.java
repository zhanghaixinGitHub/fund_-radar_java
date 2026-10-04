package com.fundradar.core.alert.service;

import com.fundradar.core.alert.api.AlertRuleResponse;
import com.fundradar.core.alert.api.AlertRulePageResponse;
import com.fundradar.core.alert.api.UpsertAlertRuleRequest;
import com.fundradar.core.auth.AuthenticatedUser;
import com.fundradar.core.auth.CurrentUserContext;
import com.fundradar.core.common.trace.TraceContext;
import com.fundradar.core.integration.ai.AiFundClient;
import com.fundradar.core.watchlist.service.WatchlistRequiredException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

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
                          AND EXISTS (SELECT 1 FROM watchlist_item item
                                      WHERE item.user_id = alert_rule.user_id AND item.fund_code = alert_rule.fund_code)
                        ORDER BY updated_at DESC, rule_id DESC
                        """)
                .param("userId", user.userId())
                .query((row, rowNumber) -> mapRule(row))
                .list();
    }

    /**
     * 仅分页查询本人仍关注的基金。状态只指接收开关，不按消息是否可用筛选。
     * 计数和列表共用数据库快照，避免并发启停造成总数与当前页不一致；越界页回退，处理最后一条被关闭的情况。
     */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AlertRulePageResponse pageCurrentUserRules(Boolean enabled, int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException("提醒页码或每页条数不正确");
        }
        UUID userId = CurrentUserContext.require().userId();
        String scope = """
                FROM alert_rule
                WHERE user_id = :userId
                  AND EXISTS (SELECT 1 FROM watchlist_item item
                              WHERE item.user_id = alert_rule.user_id AND item.fund_code = alert_rule.fund_code)
                """;
        // 仅追加固定 SQL，用户输入始终作为绑定参数，计数与列表严格共用条件。
        if (enabled != null) scope += " AND enabled = :enabled ";
        var countQuery = jdbcClient.sql("SELECT count(*) " + scope).param("userId", userId);
        if (enabled != null) countQuery.param("enabled", enabled);
        long totalCount = countQuery.query(Long.class).single();
        int totalPages = (int) ((totalCount + pageSize - 1) / pageSize);
        int currentPage = Math.min(page, Math.max(1, totalPages));
        var itemsQuery = jdbcClient.sql("""
                        SELECT rule_id, fund_code, rule_type, threshold, enabled, created_at, updated_at
                        """ + scope + " ORDER BY updated_at DESC, rule_id DESC LIMIT :limit OFFSET :offset")
                .param("userId", userId).param("limit", pageSize).param("offset", (long) (currentPage - 1) * pageSize);
        if (enabled != null) itemsQuery.param("enabled", enabled);
        return new AlertRulePageResponse(itemsQuery.query((row, index) -> mapRule(row)).list(),
                currentPage, pageSize, totalCount, totalPages);
    }

    @Override
    @Transactional
    /** 校验基金存在后插入或更新规则，记录审计信息并返回最终持久化状态。 */
    public AlertRuleResponse upsertCurrentUserRule(UpsertAlertRuleRequest request) {
        AuthenticatedUser user = CurrentUserContext.require();
        // 已取消关注时不能重新打开订阅；保留旧规则和消息历史，关闭操作仍允许。
        if (request.enabled() && !jdbcClient.sql("""
                SELECT EXISTS (SELECT 1 FROM watchlist_item WHERE user_id = :userId AND fund_code = :fundCode)
                """).param("userId", user.userId()).param("fundCode", request.fundCode()).query(Boolean.class).single()) {
            throw new WatchlistRequiredException();
        }
        // 关闭已存在的订阅只依赖本地设置；外部基金资料暂时不可读时也必须允许用户停止接收。
        boolean existingRule = jdbcClient.sql("""
                SELECT EXISTS (SELECT 1 FROM alert_rule WHERE user_id = :userId AND fund_code = :fundCode AND rule_type = :ruleType)
                """).param("userId", user.userId()).param("fundCode", request.fundCode()).param("ruleType", request.ruleType())
                .query(Boolean.class).single();
        if (request.enabled() || !existingRule) aiFundClient.getFund(request.fundCode());
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
