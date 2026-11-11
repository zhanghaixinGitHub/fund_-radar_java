package com.fundradar.core.fund.service;

import com.fundradar.core.auth.AccountRole;
import com.fundradar.core.auth.AuthenticatedUser;
import com.fundradar.core.auth.CurrentUserContext;
import com.fundradar.core.auth.PermissionCode;
import com.fundradar.core.simulation.SimulationRepository;
import com.fundradar.core.watchlist.service.WatchlistService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class FundPersonalScopeServiceTests {
    private final WatchlistService watchlist = mock(WatchlistService.class);
    private final SimulationRepository simulation = mock(SimulationRepository.class);
    private final FundPersonalScopeService service = new FundPersonalScopeService(watchlist, simulation);

    @AfterEach void clear() { CurrentUserContext.clear(); }

    @Test void combinedFiltersUseIntersectionOfOwnWatchedAndHeldFunds() {
        var user = new AuthenticatedUser(UUID.randomUUID(), "test", "测试", AccountRole.FUND_USER,
                Set.of(PermissionCode.FUND_READ));
        CurrentUserContext.set(user);
        when(watchlist.findCurrentUserFollowedFundCodes()).thenReturn(Set.of("000001", "000002"));
        when(simulation.heldFundCodes(user.userId())).thenReturn(Set.of("000002", "000003"));

        assertEquals(Set.of("000002"), service.findFundCodes(true, true));
        verify(watchlist).findCurrentUserFollowedFundCodes();
        verify(simulation).heldFundCodes(user.userId());
    }

    @Test void unselectedScopeDoesNotReadPersonalData() {
        assertEquals(Set.of(), service.findFundCodes(false, false));
        verifyNoInteractions(watchlist, simulation);
    }
}
