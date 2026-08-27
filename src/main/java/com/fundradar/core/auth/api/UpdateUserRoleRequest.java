package com.fundradar.core.auth.api;

import com.fundradar.core.auth.AccountRole;
import jakarta.validation.constraints.NotNull;

/** 系统管理员调整用户角色的请求；普通用户不能自行选择或修改角色。 */
public record UpdateUserRoleRequest(
        @NotNull(message = "角色不能为空。") AccountRole role
) {
}
