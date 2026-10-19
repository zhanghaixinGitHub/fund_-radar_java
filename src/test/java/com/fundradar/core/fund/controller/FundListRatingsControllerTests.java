package com.fundradar.core.fund.controller;

import com.fundradar.core.auth.*;
import com.fundradar.core.fund.api.*;
import com.fundradar.core.fund.service.*;
import com.fundradar.core.watchlist.service.WatchlistService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 合并响应继续执行基金读取权限，追加本人关注状态时不丢评级，并禁止浏览器缓存。 */
class FundListRatingsControllerTests {
    private final FundQueryService service = mock(FundQueryService.class);
    private final WatchlistService watchlist = mock(WatchlistService.class);
    private final FundController controller = new FundController(service, mock(FundEventQueryService.class),
            mock(FundAnalysisSummaryQueryService.class), mock(FundFeatureStatusQueryService.class),
            mock(FundSignalQueryService.class), watchlist);

    @AfterEach void clear() { CurrentUserContext.clear(); }

    private void login(PermissionCode... permissions) {
        CurrentUserContext.set(new AuthenticatedUser(UUID.randomUUID(), "13900000000", "隔离测试",
                AccountRole.FUND_USER, Set.of(permissions)));
    }

    @Test void rejectsMissingAuthenticationOrFundReadPermission() {
        assertThrows(RuntimeException.class, () -> controller.listFunds(null, 10, null, 1, null, "GOOD"));
        login(PermissionCode.WATCHLIST_SELF_READ);
        assertThrows(RuntimeException.class, () -> controller.listFunds(null, 10, null, 1, null, "GOOD"));
        verifyNoInteractions(service, watchlist);
    }

    @Test void preservesRatingAndOwnWatchStatusInANonCacheableResponse() {
        login(PermissionCode.FUND_READ);
        var rating = new FundRatingResponse.Summary("002112", "RATED", "GOOD", "良好", "2026-09-30",
                "2026-11-01T00:00:00Z", "ref", null);
        when(service.listFunds(null, null, 10, null, 1)).thenReturn(new FundPageResponse(
                List.of(new FundSummaryResponse("002112", "测试基金", "MIXED", "ACTIVE", null,
                        null, null, null, false, rating)), null, 1, 10, 1, 1, false, null));
        when(watchlist.findCurrentUserFollowedFundCodes(List.of("002112"))).thenReturn(Set.of("002112"));

        var result = controller.listFunds(null, 10, null, 1, null, null);

        assertEquals("no-store", result.getHeaders().getCacheControl());
        assertNotNull(result.getBody());
        assertTrue(result.getBody().data().items().get(0).isWatched());
        assertSame(rating, result.getBody().data().items().get(0).rating());
    }

    @Test void forwardsCombinedRatingAndKeywordFilters() {
        login(PermissionCode.FUND_READ);
        when(service.listFunds("华夏", "MIXED", 20, null, 1, "GOOD"))
                .thenReturn(new FundPageResponse(List.of(), null, 1, 20, 0, 0, false, null));
        var result = controller.listFunds("华夏", 20, null, 1, "MIXED", "GOOD");
        assertEquals(0, result.getBody().data().totalCount());
        verify(service).listFunds("华夏", "MIXED", 20, null, 1, "GOOD");
        verifyNoMoreInteractions(service);
    }
}
