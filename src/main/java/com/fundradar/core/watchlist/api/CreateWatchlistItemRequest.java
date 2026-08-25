package com.fundradar.core.watchlist.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** 当前本地用户新增一条基金关注记录的请求体。 */
public record CreateWatchlistItemRequest(
        @NotBlank(message = "基金代码不能为空。")
        @Pattern(regexp = "^\\d{6}$", message = "基金代码必须为 6 位数字。") String fundCode
) {
}
