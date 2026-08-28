package com.fundradar.core.auth.api;

import com.fundradar.core.auth.AccountRole;

import java.time.Instant;
import java.util.UUID;

/** 后台账户列表的一行，不包含密码哈希、会话或其他身份凭据。 */
public record AdminUserResponse(
        UUID userId,
        String mobileMasked,
        String displayName,
        String status,
        AccountRole role,
        long watchlistCount,
        long trialCreditTotal,
        long trialCreditLocked,
        long trialCreditAvailable,
        Instant createdAt,
        boolean legacyRecord
) {
}
