package com.fundradar.core.auth.api;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.NotBlank;

/** 管理员启用或停用用户的请求体。 */
public record UpdateUserStatusRequest(
        @NotBlank(message = "账户状态不能为空。")
        @Pattern(regexp = "^(ACTIVE|DISABLED)$", message = "账户状态只能是 ACTIVE 或 DISABLED。") String status
) {
}
