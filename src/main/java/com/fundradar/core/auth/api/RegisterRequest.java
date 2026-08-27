package com.fundradar.core.auth.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 浏览器显式注册请求；确认密码由页面校验，服务端只接收一次待哈希密码。 */
public record RegisterRequest(
        @NotBlank(message = "手机号不能为空。")
        @Pattern(regexp = "^1[3-9]\\d{9}$", message = "请输入中国大陆 11 位手机号。") String mobile,
        @NotBlank(message = "密码不能为空。")
        @Size(min = 6, max = 20, message = "密码长度必须为 6 至 20 个字符。") String password
) {
}
