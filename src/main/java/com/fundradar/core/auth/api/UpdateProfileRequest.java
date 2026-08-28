package com.fundradar.core.auth.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 当前登录账户更新公开姓名的请求；手机号、角色和权限不接受浏览器修改。 */
public record UpdateProfileRequest(
        @NotBlank(message = "姓名不能为空。")
        @Size(max = 128, message = "姓名不能超过 128 个字符。") String displayName
) {
}
