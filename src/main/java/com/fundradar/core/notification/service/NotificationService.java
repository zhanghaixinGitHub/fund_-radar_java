package com.fundradar.core.notification.service;

import com.fundradar.core.notification.api.NotificationPageResponse;
import com.fundradar.core.notification.api.NotificationResponse;

import java.util.UUID;

/** 当前用户的资讯型通知读取接口；不承担模型执行、规则投递或交易动作。 */
public interface NotificationService {

    /** 按创建时间倒序读取当前用户通知。 */
    NotificationPageResponse listCurrentUserNotifications(int page, int pageSize);

    /** 幂等标记当前用户拥有的一条通知为已读。 */
    NotificationResponse markCurrentUserNotificationRead(UUID notificationId);
}
