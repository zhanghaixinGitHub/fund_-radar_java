package com.fundradar.core.auth.api;

import com.fundradar.core.auth.AccountRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 管理员创建系统用户的请求体；权限、手机号格式与密码规则均由服务端再次验证。 */
public record CreateUserRequest(
        @NotBlank(message = "手机号不能为空。")
        @Pattern(regexp = "^1[3-9]\\d{9}$", message = "请输入中国大陆 11 位手机号。") String mobile,
        @NotBlank(message = "显示名称不能为空。")
        @Size(max = 128, message = "显示名称不能超过 128 个字符。") String displayName,
        @NotBlank(message = "密码不能为空。")
        @Size(min = 6, max = 20, message = "密码长度必须为 6 至 20 个字符。") String password,
        @NotNull(message = "角色不能为空。") AccountRole role
) {
}
