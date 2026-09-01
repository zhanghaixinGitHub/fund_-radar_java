package com.fundradar.core.notification.service;

/** 当前用户范围内不存在通知时抛出；不得暴露其他用户是否拥有该记录。 */
public class NotificationNotFoundException extends RuntimeException {

    public NotificationNotFoundException() {
        super("notification is not available for current user");
    }
}
