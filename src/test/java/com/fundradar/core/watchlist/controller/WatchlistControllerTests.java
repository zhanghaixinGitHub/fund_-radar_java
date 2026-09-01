package com.fundradar.core.watchlist.controller;

import com.fundradar.core.auth.AccountRole;
import com.fundradar.core.auth.AuthenticatedUser;
import com.fundradar.core.auth.CurrentUserContext;
import com.fundradar.core.auth.PermissionCode;
import com.fundradar.core.fund.api.FundDetailResponse;
import com.fundradar.core.fund.api.FundNavHistoryResponse;
import com.fundradar.core.fund.api.FundPageResponse;
import com.fundradar.core.fund.api.FundSameTypeComparisonResponse;
import com.fundradar.core.fund.api.FundShareHistoryResponse;
import com.fundradar.core.fund.api.FundShareSnapshotResponse;
import com.fundradar.core.fund.api.WatchlistFundDetailResponse;
import com.fundradar.core.fund.service.FundQueryService;
import com.fundradar.core.watchlist.api.WatchlistItemResponse;
import com.fundradar.core.watchlist.api.WatchlistPageResponse;
import com.fundradar.core.watchlist.service.WatchlistRequiredException;
import com.fundradar.core.watchlist.service.WatchlistService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证完整关注详情必须先通过当前用户的本地关注关系校验。 */
class WatchlistControllerTests {

    @Test
    void rejectsUnfollowedFundBeforeCallingInternalDetailService() {
        AtomicBoolean detailRequested = new AtomicBoolean(false);
        WatchlistController controller = new WatchlistController(
                new StubWatchlistService(Set.of()),
                new StubFundQueryService(detailRequested)
        );
        CurrentUserContext.set(currentUser());

        try {
            assertThrows(
                    WatchlistRequiredException.class,
                    () -> controller.getCurrentUserWatchlistFundDetail("002112")
            );
            assertFalse(detailRequested.get());
        } finally {
            CurrentUserContext.clear();
        }
    }

    @Test
    void returnsFullDetailOnlyAfterCurrentUserFollowCheck() {
        AtomicBoolean detailRequested = new AtomicBoolean(false);
        WatchlistController controller = new WatchlistController(
                new StubWatchlistService(Set.of("002112")),
                new StubFundQueryService(detailRequested)
        );
        CurrentUserContext.set(currentUser());

        try {
            WatchlistFundDetailResponse response = controller.getCurrentUserWatchlistFundDetail("002112").data();
            assertTrue(detailRequested.get());
            assertTrue(response.basic().isWatched());
        } finally {
            CurrentUserContext.clear();
        }
    }

    @Test
    void rejectsUnfollowedFundBeforeCallingShareHistoryService() {
        AtomicBoolean internalServiceRequested = new AtomicBoolean(false);
        WatchlistController controller = new WatchlistController(
                new StubWatchlistService(Set.of()),
                new StubFundQueryService(internalServiceRequested)
        );
        CurrentUserContext.set(currentUser());

        try {
            assertThrows(
                    WatchlistRequiredException.class,
                    () -> controller.getCurrentUserFundShareHistory(
                            "002112", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 8, 25)
                    )
            );
            assertFalse(internalServiceRequested.get());
        } finally {
            CurrentUserContext.clear();
        }
    }

    @Test
    void returnsShareHistoryOnlyAfterCurrentUserFollowCheck() {
        AtomicBoolean internalServiceRequested = new AtomicBoolean(false);
        WatchlistController controller = new WatchlistController(
                new StubWatchlistService(Set.of("002112")),
                new StubFundQueryService(internalServiceRequested)
        );
        CurrentUserContext.set(currentUser());

        try {
            FundShareHistoryResponse response = controller.getCurrentUserFundShareHistory(
                    "002112", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 8, 25)
            ).data();
            assertTrue(internalServiceRequested.get());
            assertTrue(response.items().size() == 1);
        } finally {
            CurrentUserContext.clear();
        }
    }

    private AuthenticatedUser currentUser() {
        return new AuthenticatedUser(
                UUID.randomUUID(),
                "13912345678",
                "基金用户",
                AccountRole.FUND_USER,
                Set.of(PermissionCode.FUND_READ, PermissionCode.WATCHLIST_SELF_READ)
        );
    }

    private static final class StubWatchlistService implements WatchlistService {

        private final Set<String> followedCodes;

        private StubWatchlistService(Set<String> followedCodes) {
            this.followedCodes = followedCodes;
        }

        @Override
        public WatchlistPageResponse listCurrentUserItems(String fundType, int page, int pageSize) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Set<String> findCurrentUserFollowedFundCodes(Collection<String> fundCodes) {
            return followedCodes.stream().filter(fundCodes::contains).collect(java.util.stream.Collectors.toSet());
        }

        @Override
        public WatchlistItemResponse addCurrentUserItem(String fundCode) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void removeCurrentUserItem(String fundCode) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class StubFundQueryService implements FundQueryService {

        private final AtomicBoolean detailRequested;

        private StubFundQueryService(AtomicBoolean detailRequested) {
            this.detailRequested = detailRequested;
        }

        @Override
        public FundPageResponse listFunds(String keyword, String fundType, int pageSize, String cursor, Integer page) {
            throw new UnsupportedOperationException();
        }

        @Override
        public FundDetailResponse getFund(String fundCode) {
            throw new UnsupportedOperationException();
        }

        @Override
        public WatchlistFundDetailResponse getWatchlistFundDetail(String fundCode) {
            detailRequested.set(true);
            return new WatchlistFundDetailResponse(
                    new FundDetailResponse(
                            fundCode, "测试基金", "MIXED", "ACTIVE", LocalDate.of(2026, 8, 25),
                            null, null, "SYNCED", "TUSHARE_PRO_FUND", null, null, null,
                            false, false, null
                    ),
                    "NOT_SYNCED", java.util.List.of(), "NOT_SYNCED", null, "NOT_SYNCED", java.util.List.of(), false, null
            );
        }

        @Override
        public FundNavHistoryResponse getFundNavHistory(String fundCode, LocalDate startDate, LocalDate endDate) {
            throw new UnsupportedOperationException();
        }

        @Override
        public FundSameTypeComparisonResponse getFundSameTypeComparison(String fundCode) {
            throw new UnsupportedOperationException();
        }

        @Override
        public FundShareHistoryResponse getFundShareHistory(String fundCode, LocalDate startDate, LocalDate endDate) {
            detailRequested.set(true);
            return new FundShareHistoryResponse(
                    "SYNCED",
                    java.util.List.of(new FundShareSnapshotResponse(
                            LocalDate.of(2026, 8, 25), new java.math.BigDecimal("123.4500"), "TUSHARE_PRO_FUND"
                    ))
            );
        }
    }
}
