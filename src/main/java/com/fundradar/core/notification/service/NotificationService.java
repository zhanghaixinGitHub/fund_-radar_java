package com.fundradar.core.notification.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fundradar.core.notification.api.NotificationPageResponse;
import com.fundradar.core.notification.api.NotificationResponse;

import java.util.UUID;
import java.util.List;
import java.time.Instant;

/** 当前用户的资讯型通知读取接口；不承担模型执行、规则投递或交易动作。 */
public interface NotificationService {

    record FeedItem(UUID id, String fundCode, String kind, String category, Long revision,
                    String lifecycle, boolean read, Instant occurredAt, JsonNode payload) {}

    record FeedPage(List<FeedItem> items, int page, long totalCount) {}

    FeedPage feed(String category, String fund, boolean unread, int page, int pageSize);

    /** 按创建时间倒序读取当前用户通知。 */
    NotificationPageResponse listCurrentUserNotifications(int page, int pageSize);

    /** 幂等标记当前用户拥有的一条通知为已读。 */
    NotificationResponse markCurrentUserNotificationRead(UUID notificationId);
}
