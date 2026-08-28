package com.fundradar.core.auth.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 系统管理员向指定账户发放试用关注积分的请求；不涉及支付或现金金额。 */
public record GrantWatchlistCreditRequest(
        @NotNull(message = "试用关注积分数量不能为空。")
        @Min(value = 1, message = "试用关注积分数量至少为 1。")
        @Max(value = 10_000, message = "试用关注积分数量不能超过 10000。")
        Integer amount,
        @NotBlank(message = "请填写试用关注积分发放原因。")
        @Size(max = 256, message = "试用关注积分发放原因不能超过 256 个字符。")
        String reason
) {
}
