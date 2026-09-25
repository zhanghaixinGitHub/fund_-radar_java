package com.fundradar.core.sync.service;

import com.fundradar.core.auth.*;
import com.fundradar.core.integration.ai.AiSyncJobClient;
import com.fundradar.core.sync.controller.SyncJobController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 资料更新沿用同步权限；无代码和不支持基金必须在调用 Python 前拒绝。 */
class FundMaterialsSyncTests {
    private final AiSyncJobClient client = mock(AiSyncJobClient.class);
    private final InternalSyncJobService service = new InternalSyncJobService(client);
    @AfterEach void clear() { CurrentUserContext.clear(); }

    @Test void rejectsMissingBlankAndUnsupportedScope() {
        for (String code : new String[]{null, "", " ", "008888", "002112.OF"}) {
            assertThrows(IllegalArgumentException.class, () -> service.startFundMaterials(code));
        }
        verifyNoInteractions(client);
    }

    @Test void requiresLoginAndStartPermission() {
        SyncJobService delegate = mock(SyncJobService.class);
        SyncJobController controller = new SyncJobController(delegate);
        assertThrows(RuntimeException.class, () -> controller.startFundMaterials("002112"));
        CurrentUserContext.set(new AuthenticatedUser(UUID.randomUUID(), "13900000000", "测试",
                AccountRole.FUND_USER, Set.of(PermissionCode.SYNC_JOB_READ)));
        assertThrows(RuntimeException.class, () -> controller.startFundMaterials("002112"));
        verifyNoInteractions(delegate);
        controller.getLatestFundMaterials();
        verify(delegate).getLatestFundMaterials();
    }

    @Test void authorizedRequestCreatesAcceptedJob() {
        SyncJobService delegate = mock(SyncJobService.class);
        SyncJobController controller = new SyncJobController(delegate);
        CurrentUserContext.set(new AuthenticatedUser(UUID.randomUUID(), "13900000000", "测试",
                AccountRole.FUND_USER, Set.of(PermissionCode.SYNC_JOB_START)));
        assertEquals(202, controller.startFundMaterials("002112").getStatusCode().value());
        verify(delegate, times(1)).startFundMaterials("002112");
    }

    @Test void latestStateMayBeAbsentWithoutTriggeringWork() {
        assertNull(service.getLatestFundMaterials());
        verify(client).getLatestFundMaterials();
        verifyNoMoreInteractions(client);
    }
}
