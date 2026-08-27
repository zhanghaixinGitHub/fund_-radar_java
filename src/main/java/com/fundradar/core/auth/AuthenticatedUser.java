package com.fundradar.core.auth;

import java.util.UUID;
import java.util.Set;

/** 当前经服务端会话校验后的用户身份；不包含密码、会话令牌或任何敏感凭据。 */
public record AuthenticatedUser(
        UUID userId,
        String mobile,
        String displayName,
        AccountRole role,
        Set<PermissionCode> permissions
) {

    /** 权限集合由服务端角色映射加载；缺失时按空集合拒绝授权，并防止调用方后续修改。 */
    public AuthenticatedUser {
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }

    /** 判断当前身份是否具备后台管理权限。 */
    public boolean isAdministrator() {
        return role == AccountRole.SYSTEM_ADMIN;
    }

    /** 判断当前用户是否被授予指定业务权限。 */
    public boolean hasPermission(PermissionCode permission) {
        return isAdministrator() || permissions.contains(permission);
    }
}
