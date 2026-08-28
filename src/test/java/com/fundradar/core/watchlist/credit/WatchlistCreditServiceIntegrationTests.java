package com.fundradar.core.watchlist.credit;

import com.fundradar.core.auth.AccountRole;
import com.fundradar.core.auth.AuthenticatedUser;
import com.fundradar.core.auth.PermissionCode;
import com.fundradar.core.watchlist.api.WatchlistCreditLedgerPageResponse;
import com.fundradar.core.watchlist.api.WatchlistQuotaResponse;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 试用关注积分的数据库级集成测试。
 *
 * <p>每个用例都在测试事务中构造独立账户和关注记录，测试结束后由 Spring 回滚，不能影响本地真实数据。</p>
 */
@SpringBootTest
@Transactional
class WatchlistCreditServiceIntegrationTests {

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private WatchlistCreditService watchlistCreditService;

    /** 第六条关注没有可用积分时必须拒绝并维持五条免费额度的边界。 */
    @Test
    void rejectsSixthWatchlistItemWhenNoTrialCreditIsAvailable() {
        AuthenticatedUser user = createActiveAccount(AccountRole.FUND_USER);
        createWatchlistItems(user, 6);

        Assertions.assertThrows(
                WatchlistQuotaExceededException.class,
                () -> watchlistCreditService.reconcileAfterWatchlistChanged(user)
        );
    }

    /** 管理员发放积分后第六条会锁定积分；取消被锁定的关注后积分应重新可用。 */
    @Test
    void locksGrantedCreditForSixthItemAndReleasesItAfterUnfollow() {
        AuthenticatedUser user = createActiveAccount(AccountRole.FUND_USER);
        AuthenticatedUser administrator = createActiveAccount(AccountRole.SYSTEM_ADMIN);
        createWatchlistItems(user, 6);

        WatchlistQuotaResponse afterGrant = watchlistCreditService.grantCredits(
                user.userId(), 1, "积分服务集成测试", administrator
        );
        Assertions.assertEquals(6, afterGrant.activeWatchlistCount());
        Assertions.assertEquals(1, afterGrant.trialCreditTotal());
        Assertions.assertEquals(0, afterGrant.trialCreditLocked());
        Assertions.assertEquals(1, afterGrant.trialCreditAvailable());

        watchlistCreditService.reconcileAfterWatchlistChanged(user);
        WatchlistQuotaResponse afterLock = watchlistCreditService.getQuota(user);
        Assertions.assertEquals(1, afterLock.trialCreditLocked());
        Assertions.assertEquals(0, afterLock.trialCreditAvailable());
        Assertions.assertEquals(6, afterLock.maxActiveWatchlistCount());

        UUID heldItemId = jdbcClient.sql("""
                        SELECT hold.watchlist_item_id
                        FROM watchlist_credit_hold hold
                        JOIN watchlist_item item ON item.watchlist_item_id = hold.watchlist_item_id
                        WHERE item.user_id = :userId
                        """)
                .param("userId", user.userId())
                .query(UUID.class)
                .single();
        watchlistCreditService.releaseItemHoldBeforeWatchlistDeletion(user, heldItemId);
        jdbcClient.sql("DELETE FROM watchlist_item WHERE watchlist_item_id = :watchlistItemId")
                .param("watchlistItemId", heldItemId)
                .update();
        watchlistCreditService.reconcileAfterWatchlistChanged(user);

        WatchlistQuotaResponse afterUnfollow = watchlistCreditService.getQuota(user);
        Assertions.assertEquals(5, afterUnfollow.activeWatchlistCount());
        Assertions.assertEquals(1, afterUnfollow.trialCreditTotal());
        Assertions.assertEquals(0, afterUnfollow.trialCreditLocked());
        Assertions.assertEquals(1, afterUnfollow.trialCreditAvailable());
    }

    /** 历史关注迁入目标账户后，迁移服务应按目标最终关注数补齐积分而不是保留旧账户额度。 */
    @Test
    void grantsAndLocksMigrationCreditForTransferredLegacyWatchlist() {
        AuthenticatedUser targetUser = createActiveAccount(AccountRole.FUND_USER);
        AuthenticatedUser administrator = createActiveAccount(AccountRole.SYSTEM_ADMIN);
        createWatchlistItems(targetUser, 6);

        watchlistCreditService.reconcileLegacyWatchlistTransfer(targetUser.userId(), administrator);

        WatchlistQuotaResponse quota = watchlistCreditService.getQuota(targetUser);
        Assertions.assertEquals(6, quota.activeWatchlistCount());
        Assertions.assertEquals(1, quota.trialCreditTotal());
        Assertions.assertEquals(1, quota.trialCreditLocked());
        Assertions.assertEquals(0, quota.trialCreditAvailable());
    }

    /** 管理员可按时间倒序读取积分流水，且返回操作人显示名和原因而非内部标识。 */
    @Test
    void listsCreditLedgerWithReasonAndAdministratorDisplayName() {
        AuthenticatedUser user = createActiveAccount(AccountRole.FUND_USER, "积分测试用户");
        AuthenticatedUser administrator = createActiveAccount(AccountRole.SYSTEM_ADMIN, "积分测试管理员");
        watchlistCreditService.grantCredits(user.userId(), 2, "人工扩容", administrator);

        WatchlistCreditLedgerPageResponse ledger = watchlistCreditService.listCreditLedger(
                user.userId(), 0, 20, administrator
        );

        Assertions.assertEquals(1, ledger.totalCount());
        Assertions.assertEquals(1, ledger.items().size());
        Assertions.assertEquals("ADMIN_GRANT", ledger.items().get(0).entryType());
        Assertions.assertEquals(2, ledger.items().get(0).creditDelta());
        Assertions.assertEquals("人工扩容", ledger.items().get(0).reason());
        Assertions.assertEquals("积分测试管理员", ledger.items().get(0).actorDisplayName());
    }

    /** 创建受真实数据库约束校验的测试账户，不包含真实姓名、手机号或凭据。 */
    private AuthenticatedUser createActiveAccount(AccountRole role) {
        return createActiveAccount(role, "积分测试账户");
    }

    private AuthenticatedUser createActiveAccount(AccountRole role, String displayName) {
        UUID userId = UUID.randomUUID();
        String mobile = "139" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
        jdbcClient.sql("""
                        INSERT INTO user_account (user_id, mobile, display_name, password_hash, role, status)
                        VALUES (:userId, :mobile, :displayName, :passwordHash, :role, 'ACTIVE')
                        """)
                .param("userId", userId)
                .param("mobile", mobile)
                .param("displayName", displayName)
                .param("passwordHash", "not-a-real-password-hash")
                .param("role", role.name())
                .update();
        return new AuthenticatedUser(
                userId,
                mobile,
                displayName,
                role,
                role == AccountRole.SYSTEM_ADMIN ? Set.of() : Set.of(PermissionCode.WATCHLIST_SELF_WRITE)
        );
    }

    /** 为同一测试账户创建互不重复的基金代码，模拟已有关注数量。 */
    private void createWatchlistItems(AuthenticatedUser user, int count) {
        for (int index = 1; index <= count; index++) {
            jdbcClient.sql("""
                            INSERT INTO watchlist_item (watchlist_item_id, user_id, fund_code, fund_type)
                            VALUES (:watchlistItemId, :userId, :fundCode, 'INDEX')
                            """)
                    .param("watchlistItemId", UUID.randomUUID())
                    .param("userId", user.userId())
                    .param("fundCode", "TEST" + user.userId().toString().substring(0, 6) + index)
                    .update();
        }
    }
}
