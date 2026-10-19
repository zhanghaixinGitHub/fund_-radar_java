package com.fundradar.core.fund.service;

import com.fundradar.core.fund.api.FundPageResponse;
import com.fundradar.core.fund.api.FundRatingResponse;
import com.fundradar.core.fund.api.FundSummaryResponse;
import com.fundradar.core.fund.cache.RedisFundReadCache;
import com.fundradar.core.integration.ai.AiFundClient;
import com.fundradar.core.integration.ai.AiFundPage;
import com.fundradar.core.integration.ai.AiFundSummary;
import com.fundradar.core.integration.ai.AiServiceUnavailableException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 列表合并评级的批量、降级及缓存边界；全部使用替身，不触发评分或真实数据写入。 */
class FundListRatingsTests {
    private final AiFundClient client = mock(AiFundClient.class);
    private final RedisFundReadCache cache = mock(RedisFundReadCache.class);
    private final InternalFundQueryService service = new InternalFundQueryService(client, cache);

    private AiFundPage sourcePage(String... codes) {
        return new AiFundPage(java.util.Arrays.stream(codes).map(code -> new AiFundSummary(
                code, "测试基金" + code, "MIXED", "ACTIVE", null, null, null, null
        )).toList(), "next", 2, 10, 35, 4);
    }

    private FundRatingResponse.Summary rating(String code, String status) {
        return new FundRatingResponse.Summary(code, status, "RATED".equals(status) ? "GOOD" : null,
                "RATED".equals(status) ? "良好" : null, "2026-09-30", "2026-11-01T00:00:00Z", "ref", null);
    }

    private void stubSource(String... codes) {
        when(client.listFunds(null, null, 10, null, 2)).thenReturn(sourcePage(codes));
    }

    @Test void returnsRatingsInListOrderWithOneBatchAndCachesOnlyBaseData() {
        stubSource("002112", "001021");
        when(client.getFundRatings("002112,001021")).thenReturn(new FundRatingResponse.Batch(List.of(
                rating("001021", "NOT_RATED"), rating("002112", "RATED")
        )));

        var result = service.listFunds(null, null, 10, null, 2);

        assertEquals(List.of("002112", "001021"), result.items().stream().map(FundSummaryResponse::fundCode).toList());
        assertEquals("GOOD", result.items().get(0).rating().grade());
        assertEquals("NOT_RATED", result.items().get(1).rating().status());
        assertEquals(2, result.page());
        assertEquals(35, result.totalCount());
        assertEquals(4, result.totalPages());
        assertEquals("next", result.nextCursor());
        assertFalse(result.stale());
        verify(client).listFunds(null, null, 10, null, 2);
        verify(client).getFundRatings("002112,001021");
        var saved = ArgumentCaptor.forClass(FundPageResponse.class);
        verify(cache).savePage(isNull(), isNull(), eq(10), isNull(), eq(2), saved.capture());
        assertTrue(saved.getValue().items().stream().allMatch(item -> item.rating() == null));
        verifyNoMoreInteractions(client);
    }

    @Test void ratingFailureKeepsFreshListAndDoesNotFallbackToAnOldRating() {
        stubSource("002112");
        when(client.getFundRatings("002112")).thenThrow(new AiServiceUnavailableException("测试故障", null));

        var result = service.listFunds(null, null, 10, null, 2);

        assertEquals("002112", result.items().get(0).fundCode());
        assertNull(result.items().get(0).rating());
        assertFalse(result.stale());
        verify(cache, never()).findPage(any(), any(), anyInt(), any(), any());
    }

    private void stubCachedPage() {
        when(client.listFunds(null, null, 10, null, 2))
                .thenThrow(new AiServiceUnavailableException("测试列表故障", null));
        var cached = new FundPageResponse(List.of(new FundSummaryResponse(
                "002112", "缓存基金", "MIXED", "ACTIVE", null, null, null, null,
                false, rating("002112", "RATED")
        )), null, 2, 10, 35, 4, false, null);
        when(cache.findPage(null, null, 10, null, 2)).thenReturn(Optional.of(
                new RedisFundReadCache.CachedPage(cached, Instant.parse("2026-10-15T00:00:00Z"))
        ));
    }

    @Test void cachedBaseDataStillUsesCurrentWithdrawalState() {
        stubCachedPage();
        when(client.getFundRatings("002112"))
                .thenReturn(new FundRatingResponse.Batch(List.of(rating("002112", "WITHDRAWN"))));

        var result = service.listFunds(null, null, 10, null, 2);

        assertTrue(result.stale());
        assertEquals(Instant.parse("2026-10-15T00:00:00Z"), result.cachedAt());
        assertEquals("WITHDRAWN", result.items().get(0).rating().status());
        assertNull(result.items().get(0).rating().grade());
    }

    @Test void unavailableRatingsNeverReuseGradesInsideCachedBaseData() {
        stubCachedPage();
        when(client.getFundRatings("002112")).thenThrow(new AiServiceUnavailableException("测试评级故障", null));

        var result = service.listFunds(null, null, 10, null, 2);

        assertTrue(result.stale());
        assertNull(result.items().get(0).rating());
    }

    @Test void missingRatingIsUnavailableWithoutAffectingOtherRows() {
        stubSource("002112", "001021");
        when(client.getFundRatings("002112,001021"))
                .thenReturn(new FundRatingResponse.Batch(List.of(rating("001021", "STALE"))));

        var result = service.listFunds(null, null, 10, null, 2);

        assertNull(result.items().get(0).rating());
        assertEquals("STALE", result.items().get(1).rating().status());
    }

    @Test void emptyPageDoesNotRequestRatings() {
        stubSource();

        assertTrue(service.listFunds(null, null, 10, null, 2).items().isEmpty());

        verify(client).listFunds(null, null, 10, null, 2);
        verifyNoMoreInteractions(client);
    }

    @Test void filteredPageReusesVerifiedRatingsWithoutSecondReadOrCache() {
        var source = sourcePage("002112");
        var summary = rating("002112", "RATED");
        when(client.listFunds("测试", "MIXED", 10, null, 2, "GOOD"))
                .thenReturn(new AiFundPage(source.items(), null, 2, 10, 11, 2, Map.of("002112", summary)));
        var result = service.listFunds("测试", "MIXED", 10, null, 2, "GOOD");
        assertSame(summary, result.items().get(0).rating());
        assertEquals(11, result.totalCount());
        assertEquals(2, result.page());
        verify(client).listFunds("测试", "MIXED", 10, null, 2, "GOOD");
        verifyNoMoreInteractions(client);
        verifyNoInteractions(cache);
    }

    @Test void oldBackendCannotSilentlyIgnoreRatingFilter() {
        when(client.listFunds(null, null, 10, null, 1, "GOOD")).thenReturn(sourcePage("002112"));
        assertThrows(AiServiceUnavailableException.class,
                () -> service.listFunds(null, null, 10, null, 1, "GOOD"));
        verifyNoInteractions(cache);
    }

    @Test void filteredRatingFailureNeverFallsBackToUnfilteredCache() {
        when(client.listFunds(null, null, 10, null, 1, "GOOD"))
                .thenThrow(new AiServiceUnavailableException("测试故障", null));
        assertThrows(AiServiceUnavailableException.class,
                () -> service.listFunds(null, null, 10, null, 1, "GOOD"));
        verifyNoInteractions(cache);
    }
}
