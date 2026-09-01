package com.fundradar.core.notification.api;

import java.util.List;

/** 当前用户通知分页结果；总数与分页项目使用相同的个人数据范围。 */
public record NotificationPageResponse(
        List<NotificationResponse> items,
        int page,
        int pageSize,
        long totalCount,
        int totalPages
) {
}
