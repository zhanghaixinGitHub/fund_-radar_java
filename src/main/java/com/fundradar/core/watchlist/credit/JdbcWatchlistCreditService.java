package com.fundradar.core.watchlist.credit;

import com.fundradar.core.auth.AuthenticatedUser;
import com.fundradar.core.common.trace.TraceContext;
import com.fundradar.core.watchlist.api.WatchlistQuotaResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 关注积分的 JDBC 实现。
 *
 * <p>每次写操作先锁定用户专属锚点，再以当前关注数推导需要锁定的积分数量；积分总额仅由追加式流水推导。</p>
 */
@Service
public class JdbcWatchlistCreditService implements WatchlistCreditService {

    private static final Logger LOGGER = LoggerFactory.getLogger(JdbcWatchlistCreditService.class);
    private static final int FREE_WATCHLIST_LIMIT = 5;
    private static final String ADMIN_GRANT = "ADMIN_GRANT";
    private static final String MIGRATION_GRANT = "MIGRATION_GRANT";
    private static final String WATCHLIST_CREDIT_LOCKED = "WATCHLIST_CREDIT_LOCKED";
    private static final String WATCHLIST_CREDIT_RELEASED = "WATCHLIST_CREDIT_RELEASED";

    private final JdbcClient jdbcClient;

    public JdbcWatchlistCreditService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public WatchlistQuotaResponse getQuota(AuthenticatedUser user) {
        return getQuota(user.userId());
    }

    private WatchlistQuotaResponse getQuota(UUID userId) {
        long activeCount = countActiveWatchlists(userId);
        long creditTotal = findCreditTotal(userId);
        long locked = countLockedCredits(userId);
        if (creditTotal < locked) {
            throw new IllegalStateException("试用关注积分锁定状态不一致。");
        }
        return new WatchlistQuotaResponse(
                FREE_WATCHLIST_LIMIT,
                activeCount,
                creditTotal,
                locked,
                creditTotal - locked,
                FREE_WATCHLIST_LIMIT + creditTotal
        );
    }

    @Override
    @Transactional
    public void lockUserQuota(AuthenticatedUser user) {
        lockUserQuota(user.userId());
    }

    private void lockUserQuota(UUID userId) {
        jdbcClient.sql("""
                        INSERT INTO watchlist_credit_account (user_id)
                        VALUES (:userId)
                        ON CONFLICT (user_id) DO NOTHING
                        """)
                .param("userId", userId)
                .update();
        jdbcClient.sql("SELECT user_id FROM watchlist_credit_account WHERE user_id = :userId FOR UPDATE")
                .param("userId", userId)
                .query(UUID.class)
                .single();
    }

    @Override
    @Transactional
    public void reconcileAfterWatchlistChanged(AuthenticatedUser user) {
        lockUserQuota(user.userId());
        reconcileQuota(user.userId(), user);
    }

    @Override
    @Transactional
    public void reconcileLegacyWatchlistTransfer(UUID targetUserId, AuthenticatedUser actor) {
        lockUserQuota(targetUserId);
        long requiredLocks = Math.max(0, countActiveWatchlists(targetUserId) - FREE_WATCHLIST_LIMIT);
        long creditTotal = findCreditTotal(targetUserId);
        if (creditTotal < requiredLocks) {
            long migrationCredit = requiredLocks - creditTotal;
            jdbcClient.sql("""
                            INSERT INTO watchlist_credit_ledger (
                                credit_ledger_id, user_id, entry_type, credit_delta, reason, actor_id
                            ) VALUES (:ledgerId, :userId, :entryType, :creditDelta, :reason, :actorId)
                            """)
                    .param("ledgerId", UUID.randomUUID())
                    .param("userId", targetUserId)
                    .param("entryType", MIGRATION_GRANT)
                    .param("creditDelta", migrationCredit)
                    .param("reason", "历史关注迁移额度补偿")
                    .param("actorId", actor.userId().toString())
                    .update();
            writeAudit(actor, "WATCHLIST_CREDIT_LEGACY_MIGRATED", targetUserId + ":" + migrationCredit);
            LOGGER.info("JdbcWatchlistCreditService.reconcileLegacyWatchlistTransfer   >>> actorId={}, targetUserId={}, migrationCredit={}",
                    actor.userId(), targetUserId, migrationCredit);
        }
        reconcileQuota(targetUserId, actor);
    }

    private void reconcileQuota(UUID userId, AuthenticatedUser actor) {
        long requiredLocks = Math.max(0, countActiveWatchlists(userId) - FREE_WATCHLIST_LIMIT);
        List<UUID> heldItemIds = findHeldWatchlistItemIds(userId);
        if (heldItemIds.size() < requiredLocks) {
            long additionalLocks = requiredLocks - heldItemIds.size();
            if (findCreditTotal(userId) - heldItemIds.size() < additionalLocks) {
                LOGGER.warn("JdbcWatchlistCreditService.reconcileAfterWatchlistChanged   >>> quota exhausted, userId={}, requiredLocks={}, locked={}",
                        userId, requiredLocks, heldItemIds.size());
                throw new WatchlistQuotaExceededException();
            }
            List<UUID> unheldItemIds = findUnheldWatchlistItemIds(userId, additionalLocks);
            if (unheldItemIds.size() != additionalLocks) {
                throw new IllegalStateException("关注积分锁定候选与关注记录不一致。");
            }
            for (UUID watchlistItemId : unheldItemIds) {
                createHold(userId, actor, watchlistItemId, "关注额度锁定");
            }
        } else if (heldItemIds.size() > requiredLocks) {
            int releaseCount = Math.toIntExact(heldItemIds.size() - requiredLocks);
            for (UUID watchlistItemId : heldItemIds.subList(0, releaseCount)) {
                releaseHold(userId, actor, watchlistItemId, "关注取消后的额度释放");
            }
        }
    }

    @Override
    @Transactional
    public void releaseItemHoldBeforeWatchlistDeletion(AuthenticatedUser user, UUID watchlistItemId) {
        lockUserQuota(user);
        releaseHold(user.userId(), user, watchlistItemId, "取消关注释放积分");
    }

    @Override
    @Transactional
    public WatchlistQuotaResponse grantCredits(UUID targetUserId, int amount, String requestedReason, AuthenticatedUser actor) {
        if (amount <= 0 || amount > 10_000) {
            throw new IllegalArgumentException("试用关注积分数量必须在 1 至 10000 之间。");
        }
        String reason = normalizeReason(requestedReason);
        if (!isActiveNonLegacyAccount(targetUserId)) {
            throw new IllegalArgumentException("目标必须是已启用的非历史账户。");
        }
        lockUserQuota(targetUserId);
        jdbcClient.sql("""
                        INSERT INTO watchlist_credit_ledger (
                            credit_ledger_id, user_id, entry_type, credit_delta, reason, actor_id
                        ) VALUES (:ledgerId, :userId, :entryType, :creditDelta, :reason, :actorId)
                        """)
                .param("ledgerId", UUID.randomUUID())
                .param("userId", targetUserId)
                .param("entryType", ADMIN_GRANT)
                .param("creditDelta", amount)
                .param("reason", reason)
                .param("actorId", actor.userId().toString())
                .update();
        writeAudit(actor, "WATCHLIST_CREDIT_GRANTED", targetUserId.toString());
        LOGGER.info("JdbcWatchlistCreditService.grantCredits   >>> actorId={}, targetUserId={}, amount={}",
                actor.userId(), targetUserId, amount);
        return getQuota(targetUserId);
    }

    private void createHold(UUID ownerUserId, AuthenticatedUser actor, UUID watchlistItemId, String reason) {
        int inserted = jdbcClient.sql("""
                        INSERT INTO watchlist_credit_hold (watchlist_item_id)
                        VALUES (:watchlistItemId)
                        ON CONFLICT (watchlist_item_id) DO NOTHING
                        """)
                .param("watchlistItemId", watchlistItemId)
                .update();
        if (inserted == 1) {
            writeLedgerEvent(ownerUserId, actor, WATCHLIST_CREDIT_LOCKED, watchlistItemId, reason);
        }
    }

    private void releaseHold(UUID ownerUserId, AuthenticatedUser actor, UUID watchlistItemId, String reason) {
        int deleted = jdbcClient.sql("DELETE FROM watchlist_credit_hold WHERE watchlist_item_id = :watchlistItemId")
                .param("watchlistItemId", watchlistItemId)
                .update();
        if (deleted == 1) {
            writeLedgerEvent(ownerUserId, actor, WATCHLIST_CREDIT_RELEASED, watchlistItemId, reason);
        }
    }

    private void writeLedgerEvent(
            UUID ownerUserId, AuthenticatedUser actor, String entryType, UUID watchlistItemId, String reason
    ) {
        jdbcClient.sql("""
                        INSERT INTO watchlist_credit_ledger (
                            credit_ledger_id, user_id, entry_type, credit_delta, watchlist_item_id, reason, actor_id
                        ) VALUES (:ledgerId, :userId, :entryType, 0, :watchlistItemId, :reason, :actorId)
                """)
                .param("ledgerId", UUID.randomUUID())
                .param("userId", ownerUserId)
                .param("entryType", entryType)
                .param("watchlistItemId", watchlistItemId)
                .param("reason", reason)
                .param("actorId", actor.userId().toString())
                .update();
        writeAudit(actor, entryType, watchlistItemId.toString());
    }

    private List<UUID> findHeldWatchlistItemIds(UUID userId) {
        return jdbcClient.sql("""
                        SELECT hold.watchlist_item_id
                        FROM watchlist_credit_hold hold
                        JOIN watchlist_item item ON item.watchlist_item_id = hold.watchlist_item_id
                        WHERE item.user_id = :userId
                        ORDER BY hold.created_at DESC, hold.watchlist_item_id ASC
                        """)
                .param("userId", userId)
                .query(UUID.class)
                .list();
    }

    private List<UUID> findUnheldWatchlistItemIds(UUID userId, long limit) {
        return jdbcClient.sql("""
                        SELECT item.watchlist_item_id
                        FROM watchlist_item item
                        LEFT JOIN watchlist_credit_hold hold ON hold.watchlist_item_id = item.watchlist_item_id
                        WHERE item.user_id = :userId
                          AND hold.watchlist_item_id IS NULL
                        ORDER BY item.created_at DESC, item.watchlist_item_id ASC
                        LIMIT :limit
                        """)
                .param("userId", userId)
                .param("limit", limit)
                .query(UUID.class)
                .list();
    }

    private long countActiveWatchlists(UUID userId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM watchlist_item WHERE user_id = :userId")
                .param("userId", userId)
                .query(Long.class)
                .single();
    }

    private long findCreditTotal(UUID userId) {
        return jdbcClient.sql("""
                        SELECT COALESCE(SUM(credit_delta), 0)
                        FROM watchlist_credit_ledger
                        WHERE user_id = :userId
                        """)
                .param("userId", userId)
                .query(Long.class)
                .single();
    }

    private long countLockedCredits(UUID userId) {
        return jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM watchlist_credit_hold hold
                        JOIN watchlist_item item ON item.watchlist_item_id = hold.watchlist_item_id
                        WHERE item.user_id = :userId
                        """)
                .param("userId", userId)
                .query(Long.class)
                .single();
    }

    private boolean isActiveNonLegacyAccount(UUID userId) {
        return jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM user_account
                        WHERE user_id = :userId
                          AND status = 'ACTIVE'
                          AND mobile <> 'legacy-local-user'
                        """)
                .param("userId", userId)
                .query(Long.class)
                .single() == 1;
    }

    private void writeAudit(AuthenticatedUser actor, String action, String targetId) {
        jdbcClient.sql("""
                        INSERT INTO audit_log (audit_log_id, trace_id, actor, action, target_id)
                        VALUES (:auditId, :traceId, :actor, :action, :targetId)
                        """)
                .param("auditId", UUID.randomUUID())
                .param("traceId", TraceContext.getTraceId())
                .param("actor", actor.userId().toString())
                .param("action", action)
                .param("targetId", targetId)
                .update();
    }

    private String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("请填写试用关注积分发放原因。");
        }
        String normalized = reason.strip();
        if (normalized.length() > 256) {
            throw new IllegalArgumentException("试用关注积分发放原因不能超过 256 个字符。");
        }
        return normalized;
    }
}
