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
        assertThrows(RuntimeException.class, () -> controller.risk("002112"));
        assertThrows(RuntimeException.class, () -> controller.evaluations("002112"));
        assertThrows(RuntimeException.class, () -> controller.news("002112"));
        assertThrows(RuntimeException.class, () -> controller.overview("002112", null, null));
        login(Set.of(PermissionCode.WATCHLIST_SELF_READ));
        assertThrows(RuntimeException.class, () -> controller.evaluations("002112"));
        assertThrows(RuntimeException.class, () -> controller.news("002112"));
        assertThrows(RuntimeException.class, () -> controller.risk("002112"));
        assertThrows(RuntimeException.class, () -> controller.documents("002112", 1, 20, "all", null, "", false));
        verifyNoInteractions(materials);
        login(Set.of(PermissionCode.FUND_READ));
        controller.overview("002112", null, null);
        controller.risk("002112");
        controller.news("002112");
        controller.evaluations("002112,001412");
        verify(materials).evaluations("002112,001412");
        verify(materials).news("002112");
        verify(materials).risk("002112");
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
    @Test void evaluationBatchHasStrictBoundedCodes() throws Exception {
        var method=FundMaterialsController.class.getMethod("evaluations",String.class);
        try(var factory=Validation.buildDefaultValidatorFactory()) {
            var validator=factory.getValidator().forExecutables();
            assertTrue(validator.validateParameters(controller,method,new Object[]{"002112,001412"}).isEmpty());
            for(String invalid:java.util.List.of("", "002112,", "../bad", "002112,".repeat(100)+"001412"))
                assertFalse(validator.validateParameters(controller,method,new Object[]{invalid}).isEmpty());
        }
    }
}
