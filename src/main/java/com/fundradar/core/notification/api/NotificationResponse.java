package com.fundradar.core.notification.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/** 当前用户可见的一条资讯型提醒通知；不包含其他用户、规则阈值或交易指令。 */
public record NotificationResponse(
        UUID notificationId,
        String fundCode,
        String ruleType,
        String triggerType,
        String triggerRef,
        String status,
        JsonNode payload,
        Instant createdAt,
        Instant readAt
) {
}
