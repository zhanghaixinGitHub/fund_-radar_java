package com.fundradar.core.auth.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 系统管理员人工重置用户密码的请求；密码不会记录在日志、审计详情或响应中。 */
public record ResetPasswordRequest(
        @NotBlank(message = "新密码不能为空。")
        @Size(min = 6, max = 20, message = "密码长度必须为 6 至 20 个字符。") String newPassword
) {
}
