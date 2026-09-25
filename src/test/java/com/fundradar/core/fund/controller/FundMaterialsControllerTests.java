package com.fundradar.core.fund.controller;

import com.fundradar.core.auth.*;
import com.fundradar.core.fund.service.FundMaterialsQueryService;
import com.fundradar.core.fund.service.FundQueryService;
import com.fundradar.core.watchlist.service.WatchlistService;
import jakarta.validation.Validation;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 公共资料仍需身份和基金读权限，分页、代码及日期边界由服务端验证。 */
class FundMaterialsControllerTests {
    private final FundMaterialsQueryService materials = mock(FundMaterialsQueryService.class);
    private final FundQueryService funds = mock(FundQueryService.class);
    private final FundMaterialsController controller = new FundMaterialsController(materials, funds,
            mock(WatchlistService.class));
    @AfterEach void clear() { CurrentUserContext.clear(); }
    private void login(Set<PermissionCode> permissions) {
        CurrentUserContext.set(new AuthenticatedUser(UUID.randomUUID(), "13900000000", "测试",
                AccountRole.FUND_USER, permissions));
    }
    @Test void requiresAuthenticatedFundReader() {
        assertThrows(RuntimeException.class, () -> controller.overview("002112", null, null));
        login(Set.of(PermissionCode.WATCHLIST_SELF_READ));
        assertThrows(RuntimeException.class, () -> controller.documents("002112", 1, 20, "all", null, "", false));
        verifyNoInteractions(materials);
        login(Set.of(PermissionCode.FUND_READ));
        controller.overview("002112", null, null);
        verify(materials).overview("002112", null, null);
    }
    @Test void rejectsUnboundedAndInvalidQueries() throws Exception {
        var method = FundMaterialsController.class.getMethod("documents", String.class, int.class, int.class,
                String.class, String.class, String.class, boolean.class);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator().forExecutables();
            assertTrue(validator.validateParameters(controller, method,
                    new Object[]{"002112", 1, 20, "company", "300308.SZ", "回购", true}).isEmpty());
            assertFalse(validator.validateParameters(controller, method,
                    new Object[]{"../bad", 0, 1000, "unknown", "../private", "x".repeat(81), true}).isEmpty());
        }
    }
    @Test void shareHistoryRejectsInvertedDatesBeforeCallingDataService() {
        login(Set.of(PermissionCode.FUND_READ));
        assertThrows(IllegalArgumentException.class, () -> controller.shares("002112",
                LocalDate.of(2026, 9, 24), LocalDate.of(2026, 9, 23)));
        verifyNoInteractions(funds);
    }
}
