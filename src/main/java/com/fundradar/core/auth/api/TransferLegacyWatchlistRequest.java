package com.fundradar.core.auth.api;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** 管理员确认历史本机关注归属后，将其转移给指定用户的请求体。 */
public record TransferLegacyWatchlistRequest(
        @NotNull(message = "目标用户不能为空。") UUID targetUserId,
        @AssertTrue(message = "请先确认历史关注迁移。") boolean confirmed
) {
}
