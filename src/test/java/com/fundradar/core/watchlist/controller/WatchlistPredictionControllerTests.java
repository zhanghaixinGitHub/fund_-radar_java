package com.fundradar.core.watchlist.controller;

import com.fundradar.core.auth.*;
import com.fundradar.core.integration.ai.*;
import com.fundradar.core.watchlist.api.WatchlistPredictionResponse;
import com.fundradar.core.watchlist.service.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.client.RestClient;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 本人关注授权顺序、实际内部HTTP契约及失败关闭；不连接真实基金或账号库。 */
class WatchlistPredictionControllerTests {
    @AfterEach void clear() { CurrentUserContext.clear(); }

    private void login(Set<PermissionCode> permissions) {
        CurrentUserContext.set(new AuthenticatedUser(UUID.randomUUID(), "synthetic-user", "测试用户",
                AccountRole.FUND_USER, permissions));
    }

    @Test void unauthenticatedCannotCallPython() {
        var watchlist = mock(WatchlistService.class);
        var client = mock(AiPredictionClient.class);
        var controller = new WatchlistPredictionController(watchlist, client);
        assertThrows(RuntimeException.class, () -> controller.read("008888", new MockHttpServletResponse()));
        verifyNoInteractions(watchlist, client);
    }

    @Test void missingPermissionCannotCallPython() {
        login(Set.of(PermissionCode.FUND_READ));
        var watchlist = mock(WatchlistService.class);
        var client = mock(AiPredictionClient.class);
        var controller = new WatchlistPredictionController(watchlist, client);
        assertThrows(RuntimeException.class, () -> controller.read("008888", new MockHttpServletResponse()));
        verifyNoInteractions(watchlist, client);
    }

    @Test void notFollowedOrOtherUsersFollowCannotCallPython() {
        login(Set.of(PermissionCode.FUND_READ, PermissionCode.WATCHLIST_SELF_READ));
        var watchlist = mock(WatchlistService.class);
        when(watchlist.findCurrentUserFollowedFundCodes(List.of("008888"))).thenReturn(Set.of());
        var client = mock(AiPredictionClient.class);
        var controller = new WatchlistPredictionController(watchlist, client);
        assertThrows(WatchlistRequiredException.class, () -> controller.read("008888", new MockHttpServletResponse()));
        verifyNoInteractions(client);
    }

    @Test void ownFollowedFundReturnsIndependentCardAndNoStore() {
        login(Set.of(PermissionCode.FUND_READ, PermissionCode.WATCHLIST_SELF_READ));
        var watchlist = mock(WatchlistService.class);
        when(watchlist.findCurrentUserFollowedFundCodes(List.of("008888"))).thenReturn(Set.of("008888"));
        var client = mock(AiPredictionClient.class);
        when(client.read("008888")).thenReturn(WatchlistPredictionResponse.unavailable("008888"));
        var response = new MockHttpServletResponse();
        var actual = new WatchlistPredictionController(watchlist, client).read("008888", response);
        assertTrue(actual.success());
        assertEquals("UNAVAILABLE", actual.data().status());
        assertNull(actual.data().upProbability());
        assertEquals("no-store, private", response.getHeader("Cache-Control"));
    }

    private String body() {
        return """
                {"fund_code":"008888","status":"MODEL_NOT_RELEASED","horizon_trading_days":20,
                 "up_probability":null,"direction":null,"latest_nav_date":"2026-09-04",
                 "research_run_id":"f70feb1a-129d-4482-b66d-f4e2e3a5425c",
                 "research_evaluated_at":"2026-09-08T10:45:55.653643Z",
                 "model_version":"CASH_RESEARCH_PROTOCOL_V1","reason_codes":["INDEPENDENT_TEST_NOT_EVALUATED"],
                 "reasons":["最终独立测试尚未完成。"],"message":"模型仍在研究验证。","disclaimer":"不构成投资建议。"}
                """;
    }

    private WatchlistPredictionResponse exchange(String body, int status, AtomicReference<String> token) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/predictions/008888", request -> {
            token.set(request.getRequestHeaders().getFirst("X-Service-Token"));
            assertNotNull(request.getRequestHeaders().getFirst("X-Trace-Id"));
            assertNull(request.getRequestHeaders().getFirst("Origin"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            request.getResponseHeaders().set("Content-Type", "application/json");
            request.sendResponseHeaders(status, bytes.length);
            request.getResponseBody().write(bytes);
            request.close();
        });
        server.start();
        try {
            var properties = new AiServiceProperties();
            properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.setToken("synthetic-test-token");
            return new AiPredictionClient(RestClient.builder(), properties).read("008888");
        } finally {
            server.stop(0);
        }
    }

    @Test void actualHttpMapsSnakeCaseAndRetainsNullProbability() throws Exception {
        var token = new AtomicReference<String>();
        var actual = exchange(body(), 200, token);
        assertEquals("MODEL_NOT_RELEASED", actual.status());
        assertEquals("synthetic-test-token", token.get());
        assertEquals("2026-09-04", actual.latestNavDate().toString());
        assertEquals(20, actual.horizonTradingDays());
        assertNull(actual.upProbability());
    }

    @Test void forgedProbabilityWrongFundAndOldProtocolFailClosed() throws Exception {
        for (String payload : List.of(
                body().replace("\"up_probability\":null", "\"up_probability\":0.88"),
                body().replace("\"fund_code\":\"008888\"", "\"fund_code\":\"001632\""),
                body().replace("CASH_RESEARCH_PROTOCOL_V1", "OLD_MODEL"),
                body().replace("MODEL_NOT_RELEASED", "AVAILABLE"))) {
            var actual = exchange(payload, 200, new AtomicReference<>());
            assertEquals("UNAVAILABLE", actual.status());
            assertNull(actual.upProbability());
            assertNull(actual.researchRunId());
        }
    }

    @Test void upstreamFailureIsNotDisplayedAsProbabilityOrInternalError() throws Exception {
        var actual = exchange("{\"private\":\"synthetic-secret\"}", 503, new AtomicReference<>());
        assertEquals("UNAVAILABLE", actual.status());
        assertFalse(actual.message().contains("synthetic-secret"));
        assertNull(actual.upProbability());
    }

    private String availableBody() {
        // 仅用于本测试TCP服务的人工响应；不是任何真实模型的发布证明。
        return """
                {"fund_code":"008888","status":"AVAILABLE","horizon_trading_days":20,
                 "up_probability":0.68,"direction":"UP","latest_nav_date":"2026-09-07",
                 "research_run_id":"f70feb1a-129d-4482-b66d-f4e2e3a5425c",
                 "model_version":"CASH_FORECAST_STORAGE_V1","reason_codes":[],"reasons":[],
                 "message":"有效结果。","disclaimer":"概率不是收益率。",
                 "forecast_id":"ff8f2cbd-696b-42f8-91db-4937bf571574","cutoff_date":"2026-09-08",
                 "target_base_date":"2026-09-08","target_end_date":"2026-10-14",
                 "generated_at":"2026-09-09T02:00:00Z",
                 "model_hash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
                """;
    }

    @Test void validStoredCashResultRetainsProbabilityIdentityAndOriginalDates() throws Exception {
        var value = exchange(availableBody(), 200, new AtomicReference<>());
        assertEquals("AVAILABLE", value.status());
        assertEquals(new java.math.BigDecimal("0.68"), value.upProbability());
        assertEquals("2026-09-08", value.cutoffDate().toString());
        assertEquals("2026-10-14", value.targetEndDate().toString());
        assertNotNull(value.forecastId());
        assertTrue(value.reasonCodes().isEmpty());
    }

    @Test void damagedOrNonAvailablePayloadNeverLeaksProbability() throws Exception {
        for (String payload : List.of(
                availableBody().replace("AVAILABLE", "STALE"),
                availableBody().replace("AVAILABLE", "MODEL_NOT_RELEASED"),
                availableBody().replace("0.68", "1.01"),
                availableBody().replace("0.68", "-0.1"),
                availableBody().replace("\"UP\"", "\"NON_UP\""),
                availableBody().replace("CASH_FORECAST_STORAGE_V1", "CASH_RESEARCH_PROTOCOL_V1"),
                availableBody().replace("2026-10-14", "2026-09-08"),
                availableBody().replace("\"cutoff_date\":\"2026-09-08\"", "\"cutoff_date\":null"),
                availableBody().replace("\"forecast_id\":\"ff8f2cbd-696b-42f8-91db-4937bf571574\"", "\"forecast_id\":null"))) {
            var value = exchange(payload, 200, new AtomicReference<>());
            assertEquals("UNAVAILABLE", value.status());
            assertNull(value.upProbability());
            assertNull(value.forecastId());
        }
    }

    @Test void staleStateKeepsOriginalDatesButNoNumbers() throws Exception {
        String payload = availableBody().replace("AVAILABLE", "STALE")
                .replace("\"up_probability\":0.68", "\"up_probability\":null")
                .replace("\"direction\":\"UP\"", "\"direction\":null")
                .replace("\"reason_codes\":[]", "\"reason_codes\":[\"FORECAST_WINDOW_ENDED\"]")
                .replace("\"reasons\":[]", "\"reasons\":[\"原预测区间已结束。\"]");
        var value = exchange(payload, 200, new AtomicReference<>());
        assertEquals("STALE", value.status());
        assertNull(value.upProbability());
        assertEquals("2026-10-14", value.targetEndDate().toString());
    }
}
