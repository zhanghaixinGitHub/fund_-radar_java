package com.fundradar.core.watchlist.credit;

import com.fundradar.core.auth.AuthenticatedUser;
import com.fundradar.core.watchlist.api.WatchlistQuotaResponse;

import java.util.UUID;

/** 试用关注积分、额度锁定和管理员发放服务；不包含支付、充值或交易。 */
public interface WatchlistCreditService {

    /** 获取指定认证用户的当前额度快照。 */
    WatchlistQuotaResponse getQuota(AuthenticatedUser user);

    /** 获取并锁住当前用户的额度锚点，供同一事务中的关注写操作串行执行。 */
    void lockUserQuota(AuthenticatedUser user);

    /** 关注记录新增后按实际数量补齐所需积分锁定；积分不足时抛出稳定业务异常并回滚关注插入。 */
    void reconcileAfterWatchlistChanged(AuthenticatedUser user);

    /** 删除关注前先释放该关注已有的积分锁定并记录流水。 */
    void releaseItemHoldBeforeWatchlistDeletion(AuthenticatedUser user, UUID watchlistItemId);

    /** 历史关注迁移完成后，按目标账户最终关注数补齐迁移积分并同步锁定状态。 */
    void reconcileLegacyWatchlistTransfer(UUID targetUserId, AuthenticatedUser actor);

    /** 仅由系统管理员发放正数试用关注积分，并返回目标用户最新额度。 */
    WatchlistQuotaResponse grantCredits(UUID targetUserId, int amount, String reason, AuthenticatedUser actor);
}
