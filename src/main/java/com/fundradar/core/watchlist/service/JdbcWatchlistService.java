package com.fundradar.core.watchlist.service;

import com.fundradar.core.auth.AuthenticatedUser;
import com.fundradar.core.auth.CurrentUserContext;
import com.fundradar.core.common.trace.TraceContext;
import com.fundradar.core.integration.ai.AiFundClient;
import com.fundradar.core.integration.ai.AiFundDetail;
import com.fundradar.core.integration.ai.AiFundSummary;
import com.fundradar.core.integration.ai.AiServiceUnavailableException;
import com.fundradar.core.watchlist.api.WatchlistFundItemResponse;
import com.fundradar.core.watchlist.api.WatchlistItemResponse;
import com.fundradar.core.watchlist.api.WatchlistPageResponse;
import com.fundradar.core.watchlist.credit.WatchlistCreditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * WatchlistService 的 JDBC 实现，按认证上下文隔离每个用户的关注记录。
 *
 * 写操作先通过 AI 基金读模型校验基金代码，再以数据库唯一约束保证幂等，并写入审计日志。
 */
@Service
public class JdbcWatchlistService implements WatchlistService {

    private static final Logger LOGGER = LoggerFactory.getLogger(JdbcWatchlistService.class);
    private static final int TYPE_BACKFILL_BATCH_SIZE = 50;
    private final JdbcClient jdbcClient;
    private final AiFundClient aiFundClient;
    private final WatchlistCreditService watchlistCreditService;

    public JdbcWatchlistService(
            JdbcClient jdbcClient,
            AiFundClient aiFundClient,
            WatchlistCreditService watchlistCreditService
    ) {
        this.jdbcClient = jdbcClient;
        this.aiFundClient = aiFundClient;
        this.watchlistCreditService = watchlistCreditService;
    }

    @Override
    /** 查询当前认证用户的关注页；历史未分类记录先批量补齐类型快照再分页。 */
    public WatchlistPageResponse listCurrentUserItems(String fundType, int page, int pageSize) {
        AuthenticatedUser user = CurrentUserContext.require();
        refreshMissingFundTypes(user);
        long totalCount = countCurrentUserItems(user, fundType);
        List<StoredWatchlistItem> records = findCurrentUserPage(user, fundType, page, pageSize);
        Map<String, AiFundSummary> summaries = new HashMap<>();
        boolean marketDataUnavailable = false;
        if (!records.isEmpty()) {
            try {
                for (AiFundSummary summary : aiFundClient.listFundSummariesByCodes(
                        records.stream().map(StoredWatchlistItem::fundCode).toList()
                )) {
                    summaries.put(summary.fundCode(), summary);
                }
            } catch (AiServiceUnavailableException exception) {
                marketDataUnavailable = true;
                LOGGER.warn(
                        "JdbcWatchlistService.listCurrentUserItems   >>> market summaries unavailable, userId={}, page={}",
                        user.userId(), page, exception
                );
            }
        }
        List<WatchlistFundItemResponse> items = records.stream()
                .map(record -> toPageItem(record, summaries.get(record.fundCode())))
                .toList();
        int totalPages = (int) ((totalCount + pageSize - 1) / pageSize);
        return new WatchlistPageResponse(
                items, page, pageSize, totalCount, totalPages, marketDataUnavailable, watchlistCreditService.getQuota(user)
        );
    }

    @Override
    /** 查询当前用户在给定基金集合中的关注代码，基金市场与详情页据此追加个人化标记。 */
    public Set<String> findCurrentUserFollowedFundCodes(Collection<String> fundCodes) {
        if (fundCodes.isEmpty()) {
            return Set.of();
        }
        AuthenticatedUser user = CurrentUserContext.require();
        return Set.copyOf(jdbcClient.sql("""
                        SELECT fund_code
                        FROM watchlist_item
                        WHERE user_id = :userId AND fund_code IN (:fundCodes)
                        """)
                .param("userId", user.userId())
                .param("fundCodes", fundCodes)
                .query(String.class)
                .list());
    }

    @Override
    @Transactional
    /** 校验基金存在后幂等插入关注记录，并记录新增或重复提交审计。 */
    public WatchlistItemResponse addCurrentUserItem(String fundCode) {
        AuthenticatedUser user = CurrentUserContext.require();
        AiFundDetail fund = aiFundClient.getFund(fundCode);
        watchlistCreditService.lockUserQuota(user);
        UUID watchlistItemId = UUID.randomUUID();
        int inserted = jdbcClient.sql("""
                        INSERT INTO watchlist_item (watchlist_item_id, user_id, fund_code, fund_type)
                        VALUES (:itemId, :userId, :fundCode, :fundType)
                        ON CONFLICT (user_id, fund_code) DO NOTHING
                        """)
                .param("itemId", watchlistItemId)
                .param("userId", user.userId())
                .param("fundCode", fundCode)
                .param("fundType", fund.fundType())
                .update();
        if (inserted == 0) {
            jdbcClient.sql("""
                            UPDATE watchlist_item
                            SET fund_type = :fundType
                            WHERE user_id = :userId AND fund_code = :fundCode AND fund_type IS NULL
                            """)
                    .param("userId", user.userId())
                    .param("fundCode", fundCode)
                    .param("fundType", fund.fundType())
                    .update();
        } else {
            watchlistCreditService.reconcileAfterWatchlistChanged(user);
        }
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
        watchlistCreditService.lockUserQuota(user);
        UUID watchlistItemId = findWatchlistItemId(fundCode, user.userId());
        int deleted = 0;
        if (watchlistItemId != null) {
            watchlistCreditService.releaseItemHoldBeforeWatchlistDeletion(user, watchlistItemId);
            deleted = jdbcClient.sql("DELETE FROM watchlist_item WHERE watchlist_item_id = :watchlistItemId")
                    .param("watchlistItemId", watchlistItemId)
                    .update();
            if (deleted == 1) {
                watchlistCreditService.reconcileAfterWatchlistChanged(user);
            }
        }
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

    /** 当前用户范围内定位关注主键；为空代表重复取消，不能读取其他用户的记录。 */
    private UUID findWatchlistItemId(String fundCode, UUID userId) {
        return jdbcClient.sql("""
                        SELECT watchlist_item_id
                        FROM watchlist_item
                        WHERE user_id = :userId AND fund_code = :fundCode
                        """)
                .param("userId", userId)
                .param("fundCode", fundCode)
                .query(UUID.class)
                .optional()
                .orElse(null);
    }

    /** 统计当前用户在可选类型筛选下的关注数，和分页查询共享完全相同的数据范围。 */
    private long countCurrentUserItems(AuthenticatedUser user, String fundType) {
        String typeCondition = fundType == null ? "" : " AND fund_type = :fundType";
        var statement = jdbcClient.sql("SELECT COUNT(*) FROM watchlist_item WHERE user_id = :userId" + typeCondition)
                .param("userId", user.userId());
        if (fundType != null) {
            statement = statement.param("fundType", fundType);
        }
        return statement.query(Long.class).single();
    }

    /** 按业务类型连续排序读取当前页；同一类型内按最近关注时间和基金代码稳定排序。 */
    private List<StoredWatchlistItem> findCurrentUserPage(
            AuthenticatedUser user, String fundType, int page, int pageSize
    ) {
        String typeCondition = fundType == null ? "" : " AND fund_type = :fundType";
        var statement = jdbcClient.sql("""
                        SELECT fund_code, fund_type, created_at
                        FROM watchlist_item
                        WHERE user_id = :userId
                        """ + typeCondition + """
                        ORDER BY CASE COALESCE(fund_type, 'OTHER')
                            WHEN 'MONEY' THEN 10
                            WHEN 'BOND' THEN 20
                            WHEN 'MIXED' THEN 30
                            WHEN 'STOCK' THEN 40
                            WHEN 'INDEX' THEN 50
                            WHEN 'QDII' THEN 60
                            WHEN 'FOF' THEN 70
                            ELSE 80
                        END, created_at DESC, fund_code ASC
                        LIMIT :pageSize OFFSET :offset
                        """)
                .param("userId", user.userId())
                .param("pageSize", pageSize)
                .param("offset", (long) (page - 1) * pageSize);
        if (fundType != null) {
            statement = statement.param("fundType", fundType);
        }
        return statement.query((row, rowNumber) -> new StoredWatchlistItem(
                row.getString("fund_code"),
                row.getString("fund_type"),
                row.getTimestamp("created_at").toInstant()
        )).list();
    }

    /** 为 V8 迁移前的历史关注批量补齐类型快照；失败时保留记录并在页面标识行情不可用。 */
    private void refreshMissingFundTypes(AuthenticatedUser user) {
        List<String> missingCodes = jdbcClient.sql("""
                        SELECT fund_code
                        FROM watchlist_item
                        WHERE user_id = :userId AND fund_type IS NULL
                        ORDER BY created_at DESC, fund_code ASC
                        LIMIT :batchSize
                        """)
                .param("userId", user.userId())
                .param("batchSize", TYPE_BACKFILL_BATCH_SIZE)
                .query(String.class)
                .list();
        if (missingCodes.isEmpty()) {
            return;
        }
        try {
            for (AiFundSummary summary : aiFundClient.listFundSummariesByCodes(missingCodes)) {
                jdbcClient.sql("""
                                UPDATE watchlist_item
                                SET fund_type = :fundType
                                WHERE user_id = :userId AND fund_code = :fundCode AND fund_type IS NULL
                                """)
                        .param("fundType", summary.fundType())
                        .param("userId", user.userId())
                        .param("fundCode", summary.fundCode())
                        .update();
            }
        } catch (AiServiceUnavailableException exception) {
            LOGGER.warn(
                    "JdbcWatchlistService.refreshMissingFundTypes   >>> type backfill unavailable, userId={}, count={}",
                    user.userId(), missingCodes.size(), exception
            );
        }
    }

    /** 合并关注记录与批量行情摘要；不存在或不可用的行情字段保持为空而不伪造值。 */
    private WatchlistFundItemResponse toPageItem(StoredWatchlistItem record, AiFundSummary summary) {
        if (summary == null) {
            return new WatchlistFundItemResponse(
                    record.fundCode(), "基金信息暂缺", record.fundType() == null ? "OTHER" : record.fundType(),
                    null, null, null, null, record.createdAt()
            );
        }
        return new WatchlistFundItemResponse(
                summary.fundCode(), summary.fundName(), summary.fundType(), summary.asOfDate(),
                summary.dayChangeRate(), summary.weekChangeRate(), summary.monthChangeRate(), record.createdAt()
        );
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

    /** 关注表分页前的最小持久化投影，避免在内存中加载完整行情历史。 */
    private record StoredWatchlistItem(String fundCode, String fundType, java.time.Instant createdAt) {
    }
}
