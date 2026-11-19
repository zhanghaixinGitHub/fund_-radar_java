package com.fundradar.core.alert.service;

import com.fundradar.core.alert.api.AlertAvailabilityResponse;
import com.fundradar.core.notification.service.ReviewNoticeScheduler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import java.util.Set;

/** 依据本实例实际加载的消息检查器报告能力；仅保存订阅不能冒充消息通路已启用。 */
@Service
public class AlertAvailabilityService {
    private final ObjectProvider<ReviewNoticeScheduler> reviews;

    public AlertAvailabilityService(ObjectProvider<ReviewNoticeScheduler> reviews) {
        this.reviews = reviews;
    }

    public AlertAvailabilityResponse current() {
        boolean enabled = reviews.getIfAvailable() != null;
        return new AlertAvailabilityResponse(enabled, Set.of(), enabled, enabled);
    }
}
