package com.fundradar.core.fund.controller;

import com.fundradar.core.auth.*;
import com.fundradar.core.fund.api.FundManagerDirectory;
import com.fundradar.core.fund.service.FundManagerDirectoryService;
import com.fundradar.core.integration.ai.AiFundManagerDirectoryClient;
import com.fundradar.core.sync.controller.FundManagerSyncController;
import jakarta.validation.Validation;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 经理公共资料和管理员更新分别校验权限，分页和输入范围由服务端约束。 */
class FundManagerDirectoryControllerTests {
    private final FundManagerDirectoryService service = mock(FundManagerDirectoryService.class);
    private final FundManagerDirectoryController controller = new FundManagerDirectoryController(service);
    @AfterEach void clear() { CurrentUserContext.clear(); }
    private void login(PermissionCode... permissions) {
        CurrentUserContext.set(new AuthenticatedUser(UUID.randomUUID(), "13900000000", "测试", AccountRole.FUND_USER, Set.of(permissions)));
    }
    @Test void allReadEntrypointsRequireFundReadPermission() {
        UUID id = UUID.randomUUID(), fund = UUID.randomUUID();
        assertThrows(RuntimeException.class, () -> controller.detail(id, null));
        login(PermissionCode.WATCHLIST_SELF_READ);
        assertThrows(RuntimeException.class, () -> controller.list("", "", "", "all", 1, 20, null));
        assertThrows(RuntimeException.class, () -> controller.funds(id, "current", 1, 20, null));
        assertThrows(RuntimeException.class, () -> controller.related(id, fund, null));
        assertThrows(RuntimeException.class, () -> controller.links("002112"));
        assertThrows(RuntimeException.class, () -> controller.disclosures(id));
        verifyNoInteractions(service);
        login(PermissionCode.FUND_READ);
        controller.detail(id, null);
        controller.related(id, fund, null);
        controller.links("002112");
        verify(service).detail(id, null);
        verify(service).related(id, fund, null);
        verify(service).links("002112");
    }
    @Test void managerRefreshRequiresSeparateStartPermission() {
        var client = mock(AiFundManagerDirectoryClient.class);
        var sync = new FundManagerSyncController(client);
        var body = new FundManagerDirectory.SyncRequest(List.of(), UUID.randomUUID());
        login(PermissionCode.FUND_READ);
        assertThrows(RuntimeException.class, () -> sync.start(body));
        assertThrows(RuntimeException.class, sync::latest);
        verifyNoInteractions(client);
        login(PermissionCode.SYNC_JOB_START);
        sync.start(body);
        verify(client).start(body);
    }
    @Test void validatesPaginationSearchAndFundAnchor() throws Exception {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator().forExecutables();
            var method = FundManagerDirectoryController.class.getMethod("list", String.class, String.class,
                    String.class, String.class, int.class, int.class, UUID.class);
            assertTrue(validator.validateParameters(controller, method, new Object[]{"陆阳", "德邦", "", "all", 1, 20, null}).isEmpty());
            assertFalse(validator.validateParameters(controller, method, new Object[]{"a".repeat(129), "", "bad", "unknown", 0, 5000, null}).isEmpty());
        }
    }
    @Test void conflictResponseDoesNotExposeInternalError() {
        var response = controller.unavailable(new AiFundManagerDirectoryClient.DirectoryException(409));
        assertEquals(409, response.getStatusCode().value());
        assertEquals("SNAPSHOT_EXPIRED", response.getBody().code());
    }
}
