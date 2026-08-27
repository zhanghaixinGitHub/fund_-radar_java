package com.fundradar.core.auth.api;

import java.util.List;

/** 后台用户分页响应，避免用户规模扩大后一次性读取全部账号。 */
public record AdminUserPageResponse(
        List<AdminUserResponse> items,
        long total,
        int page,
        int pageSize
) {
}
