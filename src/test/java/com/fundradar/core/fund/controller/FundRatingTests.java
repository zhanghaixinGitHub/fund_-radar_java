package com.fundradar.core.fund.controller;

import com.fundradar.core.auth.*;
import com.fundradar.core.fund.api.FundRatingResponse;
import com.fundradar.core.fund.service.FundRatingQueryService;
import com.fundradar.core.integration.ai.AiFundClient;
import com.fundradar.core.sync.controller.SyncJobController;
import com.fundradar.core.sync.service.SyncJobService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 权限、参数、同基金公共语义与正式数字分的只读 DTO 契约。 */
class FundRatingTests {
    @AfterEach void clear() { CurrentUserContext.clear(); }
    private void login(PermissionCode... permissions) {
        CurrentUserContext.set(new AuthenticatedUser(UUID.randomUUID(), "13900000000", "隔离测试",
                AccountRole.FUND_USER, Set.of(permissions)));
    }

    @Test void readRequiresAuthenticatedFundReadPermission() {
        var service = mock(FundRatingQueryService.class);
        var controller = new FundRatingController(service);
        assertThrows(RuntimeException.class, () -> controller.batch("002112"));
        login(PermissionCode.WATCHLIST_SELF_READ);
        assertThrows(RuntimeException.class, () -> controller.detail("002112", null));
        verifyNoInteractions(service);
        login(PermissionCode.FUND_READ);
        assertEquals("no-store", controller.batch("002112").getHeaders().getCacheControl());
        controller.detail("002112", null);
        login(PermissionCode.FUND_READ); // 第二个身份走相同公共输入，不带用户过滤条件。
        controller.detail("002112", null);
        verify(service, times(2)).detail("002112", null);
    }

    @Test void inputBoundaryRejectsEmptyUnicodeAndUnboundedCodes() {
        var client = mock(AiFundClient.class);
        var service = new FundRatingQueryService(client);
        for (String codes : new String[]{null, "", " ", "００２１１２", "002112,", "002112,".repeat(100) + "001412"})
            assertThrows(IllegalArgumentException.class, () -> service.batch(codes));
        assertThrows(IllegalArgumentException.class, () -> service.detail("002112", "../private"));
        verifyNoInteractions(client);
        service.batch("002112,001412,002112");
        verify(client).getFundRatings("002112,001412");
    }

    @Test void synchronizationNeedsPermissionAndExplicitSingleScope() {
        var service = mock(SyncJobService.class);
        var controller = new SyncJobController(service);
        assertThrows(RuntimeException.class, controller::startFundRatings);
        login(PermissionCode.SYNC_JOB_READ);
        assertThrows(RuntimeException.class, controller::startFundRatings);
        login(PermissionCode.SYNC_JOB_START);
        for (String code : new String[]{null, "", " ", "００２１１２", "002112.OF"})
            assertThrows(IllegalArgumentException.class,
                    () -> controller.startFundRating(new SyncJobController.RatingSingleRequest(code)));
        verifyNoInteractions(service);
        assertEquals(202, controller.startFundRating(new SyncJobController.RatingSingleRequest("002112"))
                .getStatusCode().value());
        controller.startFundRatings();
        verify(service).startFundRatings("002112");
        verify(service).startFundRatings(null);
    }

    @Test void publicDtoOnlyAddsPublishedScoresToDetailAndDimensions() {
        for (Class<?> type : new Class<?>[]{FundRatingResponse.class, FundRatingResponse.Summary.class,
                FundRatingResponse.Dimension.class, FundRatingResponse.Comparison.class})
            for (var component : type.getRecordComponents())
                assertFalse(component.getName().matches("(?i).*(rawScore|weight|methodology|model|version).*"));
        assertFalse(java.util.Arrays.stream(FundRatingResponse.Summary.class.getRecordComponents())
                .anyMatch(c -> c.getName().equals("score")));
    }

    @Test void jsonKeepsPublishedNumericScoresAndMissingScoresStayNull() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var detail = mapper.readValue("""
                {"fundCode":"002112","status":"RATED","score":"64.12",
                 "dimensions":[{"key":"liquidity","score":"52.42","explanation":"报告日账面覆盖"}]}
                """, FundRatingResponse.class);
        assertEquals("64.12", detail.score());
        assertEquals("52.42", detail.dimensions().get(0).score());
        var oldResponse = mapper.readValue("{\"fundCode\":\"002112\",\"status\":\"NOT_RATED\"}",
                FundRatingResponse.class);
        assertNull(oldResponse.score());
    }

    @Test void partialScoreAndRangeNeverBecomeCompleteScoreOrGrade() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var detail = mapper.readValue("""
                {"fundCode":"000009","status":"PARTIAL","score":null,"grade":null,
                 "scoreRange":{"lower":"65.57","upper":"67.37"},"partialScore":"67.11",
                 "scoreCoverage":{"pointWeight":"65","intervalWeight":"35","missingWeight":"0",
                                  "pointDimensions":5,"intervalDimensions":3},
                 "dimensions":[{"key":"holdings","score":null,
                                "scoreRange":{"lower":"59.61","upper":"70.28"}}]}
                """, FundRatingResponse.class);
        assertEquals("PARTIAL", detail.status());
        assertNull(detail.score());
        assertNull(detail.grade());
        assertEquals("65.57", detail.scoreRange().lower());
        assertEquals("65", detail.scoreCoverage().pointWeight());
        assertEquals("59.61", detail.dimensions().get(0).scoreRange().lower());
    }
}
