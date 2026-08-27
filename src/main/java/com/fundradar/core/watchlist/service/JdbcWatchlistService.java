package com.fundradar.core.watchlist.service;

import com.fundradar.core.auth.AuthenticatedUser;
import com.fundradar.core.auth.CurrentUserContext;
import com.fundradar.core.common.trace.TraceContext;
import com.fundradar.core.integration.ai.AiFundClient;
import com.fundradar.core.watchlist.api.WatchlistItemResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * WatchlistService 的 JDBC 实现，按认证上下文隔离每个用户的关注记录。
 *
 * 写操作先通过 AI 基金读模型校验基金代码，再以数据库唯一约束保证幂等，并写入审计日志。
 */
@Service
public class JdbcWatchlistService implements WatchlistService {

    private static final Logger LOGGER = LoggerFactory.getLogger(JdbcWatchlistService.class);
    private final JdbcClient jdbcClient;
    private final AiFundClient aiFundClient;

    public JdbcWatchlistService(JdbcClient jdbcClient, AiFundClient aiFundClient) {
        this.jdbcClient = jdbcClient;
        this.aiFundClient = aiFundClient;
    }

    @Override
    /** 查询当前认证用户的关注记录，不访问外部数据源。 */
    public List<WatchlistItemResponse> listCurrentUserItems() {
        AuthenticatedUser user = CurrentUserContext.require();
        return jdbcClient.sql("""
                        SELECT fund_code, created_at
                        FROM watchlist_item
                        WHERE user_id = :userId
                        ORDER BY created_at DESC, fund_code ASC
                        """)
                .param("userId", user.userId())
                .query((row, rowNumber) -> new WatchlistItemResponse(
                        row.getString("fund_code"),
                        row.getTimestamp("created_at").toInstant()
                ))
                .list();
    }

    @Override
    @Transactional
    /** 校验基金存在后幂等插入关注记录，并记录新增或重复提交审计。 */
    public WatchlistItemResponse addCurrentUserItem(String fundCode) {
        AuthenticatedUser user = CurrentUserContext.require();
        aiFundClient.getFund(fundCode);
        int inserted = jdbcClient.sql("""
                        INSERT INTO watchlist_item (watchlist_item_id, user_id, fund_code)
                        VALUES (:itemId, :userId, :fundCode)
                        ON CONFLICT (user_id, fund_code) DO NOTHING
                        """)
                .param("itemId", UUID.randomUUID())
                .param("userId", user.userId())
                .param("fundCode", fundCode)
                .update();
        WatchlistItemResponse item = findRequiredItem(fundCode, user.userId());
        writeAudit(user, inserted == 1 ? "WATCHLIST_ADDED" : "WATCHLIST_ADD_IDEMPOTENT", fundCode);
        LOGGER.info("JdbcWatchlistService.addCurrentUserItem   >>> userId={}, fundCode={}, inserted={}",
                user.userId(), fundCode, inserted == 1);
        return item;
    }

    @Override
    @Transactional
    /** 幂等删除关注记录，并记录删除或重复删除审计。 */
    public void removeCurrentUserItem(String fundCode) {
        AuthenticatedUser user = CurrentUserContext.require();
        int deleted = jdbcClient.sql("""
                        DELETE FROM watchlist_item
                        WHERE user_id = :userId AND fund_code = :fundCode
                        """)
                .param("userId", user.userId())
                .param("fundCode", fundCode)
                .update();
        writeAudit(user, deleted == 1 ? "WATCHLIST_REMOVED" : "WATCHLIST_REMOVE_IDEMPOTENT", fundCode);
        LOGGER.info("JdbcWatchlistService.removeCurrentUserItem   >>> userId={}, fundCode={}, deleted={}",
                user.userId(), fundCode, deleted == 1);
    }

    /** 查询刚插入或既有的关注记录；理论上缺失表示数据库写入出现不一致。 */
    private WatchlistItemResponse findRequiredItem(String fundCode, UUID userId) {
        return jdbcClient.sql("""
                        SELECT fund_code, created_at
                        FROM watchlist_item
                        WHERE user_id = :userId AND fund_code = :fundCode
                        """)
                .param("userId", userId)
                .param("fundCode", fundCode)
                .query((row, rowNumber) -> new WatchlistItemResponse(
                        row.getString("fund_code"),
                        row.getTimestamp("created_at").toInstant()
                ))
                .optional()
                .orElseThrow(() -> new IllegalStateException("watchlist insert did not produce a row"));
    }

    /** 将关注操作及当前追踪标识写入审计表，避免记录任何敏感凭据。 */
    private void writeAudit(AuthenticatedUser user, String action, String fundCode) {
        jdbcClient.sql("""
                        INSERT INTO audit_log (audit_log_id, trace_id, actor, action, target_id)
                        VALUES (:auditId, :traceId, :actor, :action, :targetId)
                        """)
                .param("auditId", UUID.randomUUID())
                .param("traceId", TraceContext.getTraceId())
                .param("actor", user.userId().toString())
                .param("action", action)
                .param("targetId", fundCode)
                .update();
    }
}
