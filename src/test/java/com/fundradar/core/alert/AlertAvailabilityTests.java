package com.fundradar.core.alert;

import com.fundradar.core.alert.controller.AlertRuleController;
import com.fundradar.core.alert.service.*;
import com.fundradar.core.auth.*;
import com.fundradar.core.notification.service.ReviewNoticeScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AlertAvailabilityTests {
    @Test void pagingRequiresSelfReadPermission() {
        var service = mock(AlertRuleService.class);
        var controller = new AlertRuleController(service, mock(AlertAvailabilityService.class));
        try {
            CurrentUserContext.clear();
            assertThrows(RuntimeException.class, () -> controller.pageAlertRules(null, 1, 10));
            CurrentUserContext.set(new AuthenticatedUser(UUID.randomUUID(), "test", "测试", AccountRole.FUND_USER, Set.of()));
            assertThrows(RuntimeException.class, () -> controller.pageAlertRules(false, 1, 10));
            verifyNoInteractions(service);
            CurrentUserContext.set(new AuthenticatedUser(UUID.randomUUID(), "test", "测试", AccountRole.FUND_USER, Set.of(PermissionCode.ALERT_RULE_SELF_READ)));
            controller.pageAlertRules(false, 2, 10);
            verify(service).pageCurrentUserRules(false, 2, 10);
        } finally { CurrentUserContext.clear(); }
    }
    @Test void availableOnlyWhenCheckerActuallyExistsAndOnlyForCoveredFund() {
        var beans=new StaticListableBeanFactory();
        var service=new AlertAvailabilityService(beans.getBeanProvider(ReviewNoticeScheduler.class));
        assertFalse(service.current().eventAvailable());
        beans.addBean("reviews",mock(ReviewNoticeScheduler.class));
        assertTrue(service.current().eventAvailable());
        assertEquals(Set.of("002112"),service.current().eventFundCodes());
        assertFalse(service.current().signalChangeAvailable());assertFalse(service.current().riskLevelAvailable());
    }
    @Test void availabilityRequiresSelfReadPermission() {
        var service=mock(AlertAvailabilityService.class);
        var controller=new AlertRuleController(mock(AlertRuleService.class),service);
        try {
            CurrentUserContext.clear();assertThrows(RuntimeException.class,controller::availability);
            CurrentUserContext.set(new AuthenticatedUser(UUID.randomUUID(),"test","测试",AccountRole.FUND_USER,Set.of()));
            assertThrows(RuntimeException.class,controller::availability);verifyNoInteractions(service);
            CurrentUserContext.set(new AuthenticatedUser(UUID.randomUUID(),"test","测试",AccountRole.FUND_USER,Set.of(PermissionCode.ALERT_RULE_SELF_READ)));
            controller.availability();verify(service).current();
        } finally { CurrentUserContext.clear(); }
    }
}
