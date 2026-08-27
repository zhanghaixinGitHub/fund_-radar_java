package com.fundradar.core.auth;

import com.fundradar.core.auth.service.AccessDeniedException;
import com.fundradar.core.auth.service.AuthenticationRequiredException;

/** 请求线程范围的认证上下文；拦截器在进入业务 API 前写入，并在请求完成后清理。 */
public final class CurrentUserContext {

    private static final ThreadLocal<AuthenticatedUser> CURRENT_USER = new ThreadLocal<>();

    /** 工具类禁止实例化。 */
    private CurrentUserContext() {
    }

    /** 写入已经完成服务端认证的当前用户。 */
    public static void set(AuthenticatedUser user) {
        CURRENT_USER.set(user);
    }

    /** 返回当前登录用户；缺失认证上下文时拒绝继续访问。 */
    public static AuthenticatedUser require() {
        AuthenticatedUser user = CURRENT_USER.get();
        if (user == null) {
            throw new AuthenticationRequiredException();
        }
        return user;
    }

    /** 返回具备指定业务权限的当前用户；角色不足时拒绝访问，不由前端导航承担权限边界。 */
    public static AuthenticatedUser requirePermission(PermissionCode permission) {
        AuthenticatedUser user = require();
        if (!user.hasPermission(permission)) {
            throw new AccessDeniedException();
        }
        return user;
    }

    /** 返回系统管理员身份；后台账户管理不能仅依赖前端可见性或普通数据运营角色。 */
    public static AuthenticatedUser requireAdministrator() {
        AuthenticatedUser user = require();
        if (!user.isAdministrator()) {
            throw new AccessDeniedException();
        }
        return user;
    }

    /** 在请求结束或出现异常时清理线程变量，避免 Tomcat 线程复用串号。 */
    public static void clear() {
        CURRENT_USER.remove();
    }
}
