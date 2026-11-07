package com.fundradar.core.fund.controller;

import com.fundradar.core.auth.*;
import com.fundradar.core.fund.api.FundShareClassesResponse;
import com.fundradar.core.fund.service.FundShareClassQueryService;
import com.fundradar.core.integration.ai.AiFundClient;
import com.fundradar.core.integration.ai.AiServiceUnavailableException;
import jakarta.validation.Validation;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 公共关系的权限与身份边界，防止跨基金响应或未知结果被冒充成已核实。 */
class FundShareClassControllerTests {
    @AfterEach void clear() { CurrentUserContext.clear(); }
    @Test void requiresLoginAndFundReadPermission() {
        var service = mock(FundShareClassQueryService.class);
        var controller = new FundShareClassController(service);
        assertThrows(RuntimeException.class, () -> controller.read("000001"));
        CurrentUserContext.set(new AuthenticatedUser(UUID.randomUUID(), "13900000000", "测试",
                AccountRole.FUND_USER, Set.of(PermissionCode.WATCHLIST_SELF_READ)));
        assertThrows(RuntimeException.class, () -> controller.read("000001"));
        verifyNoInteractions(service);
        CurrentUserContext.set(new AuthenticatedUser(UUID.randomUUID(), "13900000000", "测试",
                AccountRole.FUND_USER, Set.of(PermissionCode.FUND_READ)));
        controller.read("000001");
        verify(service).read("000001");
    }

    @Test void validatesExactCodeAndDisablesResponseCaching() throws Exception {
        var controller = new FundShareClassController(mock(FundShareClassQueryService.class));
        var method = FundShareClassController.class.getMethod("read", String.class);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator().forExecutables();
            assertFalse(validator.validateParameters(controller, method, new Object[]{"../bad"}).isEmpty());
            assertTrue(validator.validateParameters(controller, method, new Object[]{"000001"}).isEmpty());
        }
    }

    @Test void rejectsMixedIdentityDuplicateMembersAndUnverifiedFacts() {
        var client = mock(AiFundClient.class);
        var service = new FundShareClassQueryService(client);
        var share = new FundShareClassesResponse.Share("000001", "测试基金A", "A", "人民币");
        var date = LocalDate.of(2026, 9, 30);
        for (var bad : List.of(
                new FundShareClassesResponse("000002", "VERIFIED", date, Instant.now(), List.of(share)),
                new FundShareClassesResponse("000001", "VERIFIED", date, Instant.now(), List.of(share, share)),
                new FundShareClassesResponse("000001", "UNVERIFIED", null, null, List.of(share)),
                new FundShareClassesResponse("000001", "VERIFIED", date, Instant.now(), List.of()))) {
            when(client.getFundShareClasses("000001")).thenReturn(bad);
            assertThrows(AiServiceUnavailableException.class, () -> service.read("000001"));
        }
        var good = new FundShareClassesResponse("000001", "VERIFIED", date, Instant.now(), List.of(share));
        when(client.getFundShareClasses("000001")).thenReturn(good);
        assertSame(good, service.read("000001"));
    }
}
