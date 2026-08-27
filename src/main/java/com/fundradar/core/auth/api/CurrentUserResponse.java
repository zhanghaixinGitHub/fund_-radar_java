package com.fundradar.core.auth.api;

import com.fundradar.core.auth.AccountRole;
import com.fundradar.core.auth.AuthenticatedUser;

import java.util.UUID;
import java.util.Set;

/** 提供给已登录浏览器的非敏感账户资料。 */
public record CurrentUserResponse(
        UUID userId,
        String mobileMasked,
        String displayName,
        AccountRole role,
        Set<com.fundradar.core.auth.PermissionCode> permissions
) {

    /** 将认证上下文映射为公开资料，不返回密码哈希、Cookie 或会话标识。 */
    public static CurrentUserResponse from(AuthenticatedUser user) {
        return new CurrentUserResponse(
                user.userId(),
                maskMobile(user.mobile()),
                user.displayName(),
                user.role(),
                user.permissions()
        );
    }

    private static String maskMobile(String mobile) {
        if (mobile == null || !mobile.matches("^1[3-9]\\d{9}$")) {
            return "历史账户";
        }
        return mobile.substring(0, 3) + "****" + mobile.substring(7);
    }
}
