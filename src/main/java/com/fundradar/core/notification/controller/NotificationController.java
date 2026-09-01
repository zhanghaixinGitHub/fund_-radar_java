package com.fundradar.core.notification.controller;

import com.fundradar.core.auth.CurrentUserContext;
import com.fundradar.core.auth.PermissionCode;
import com.fundradar.core.common.api.ApiResponse;
import com.fundradar.core.notification.api.NotificationPageResponse;
import com.fundradar.core.notification.api.NotificationResponse;
import com.fundradar.core.notification.service.NotificationService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 当前用户提醒通知接口；只提供信息提示，不含任何交易能力。 */
@Validated
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /** 分页读取当前用户通知。 */
    @GetMapping
    public ApiResponse<NotificationPageResponse> listNotifications(
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码至少为 1。") int page,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "每页数量至少为 1。")
            @Max(value = 100, message = "每页数量不能超过 100。") int pageSize
    ) {
        CurrentUserContext.requirePermission(PermissionCode.NOTIFICATION_SELF_READ);
        return ApiResponse.success(notificationService.listCurrentUserNotifications(page, pageSize));
    }

    /** 幂等标记当前用户拥有的一条通知为已读，越权时按不存在处理。 */
    @PostMapping("/{notificationId}/read")
    public ApiResponse<NotificationResponse> markRead(@PathVariable UUID notificationId) {
        CurrentUserContext.requirePermission(PermissionCode.NOTIFICATION_SELF_WRITE);
        return ApiResponse.success(notificationService.markCurrentUserNotificationRead(notificationId));
    }
}
