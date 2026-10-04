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
        // 当前公告核验/检查只覆盖 002112；方向和风险评分消费者尚未接通，不以存在规则推定可用。
        return new AlertAvailabilityResponse(reviews.getIfAvailable() != null, Set.of("002112"), false, false);
    }
}
