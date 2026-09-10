package com.fundradar.core.watchlist.controller;

import com.fundradar.core.auth.*;
import com.fundradar.core.integration.ai.*;
import com.fundradar.core.watchlist.api.DirectionExperimentResponse;
import com.fundradar.core.watchlist.service.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.client.RestClient;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 实验接口独立鉴权、真实HTTP字段映射和坏数字失败关闭；使用合成账号与响应。 */
class DirectionExperimentControllerTests {
    @AfterEach void clear() { CurrentUserContext.clear(); }

    private void login(Set<PermissionCode> permissions) {
        CurrentUserContext.set(new AuthenticatedUser(UUID.randomUUID(), "synthetic", "测试",
                AccountRole.FUND_USER, permissions));
    }

    @Test void experimentRequiresLoginBothPermissionsAndOwnFollow() {
        var watchlist = mock(WatchlistService.class);
        var client = mock(AiPredictionClient.class);
        var controller = new WatchlistPredictionController(watchlist, client);
        assertThrows(RuntimeException.class, () -> controller.readExperiment("008888", new MockHttpServletResponse()));
        login(Set.of(PermissionCode.FUND_READ));
        assertThrows(RuntimeException.class, () -> controller.readExperiment("008888", new MockHttpServletResponse()));
        verifyNoInteractions(watchlist, client);
        login(Set.of(PermissionCode.FUND_READ, PermissionCode.WATCHLIST_SELF_READ));
        when(watchlist.findCurrentUserFollowedFundCodes(List.of("008888"))).thenReturn(Set.of());
        assertThrows(WatchlistRequiredException.class,
                () -> controller.readExperiment("008888", new MockHttpServletResponse()));
        verifyNoInteractions(client);
        when(watchlist.findCurrentUserFollowedFundCodes(List.of("008888"))).thenReturn(Set.of("008888"));
        when(client.readExperiment("008888")).thenReturn(DirectionExperimentResponse.unavailable("008888"));
        var response = new MockHttpServletResponse();
        assertTrue(controller.readExperiment("008888", response).success());
        assertEquals("no-store, private", response.getHeader("Cache-Control"));
        verify(client).readExperiment("008888");
        verify(client, never()).read(anyString());
    }

    private String body() {
        return """
                {"fund_code":"008888","version":"DIRECTION_PAGE_TRIAL_V1","status":"EXPERIMENTAL",
                 "model_released":false,"horizon_trading_days":20,
                 "research_run_id":"0c0e06a9-725e-4b68-b813-de6ff5124b29",
                 "cutoff_date":"2026-09-09","latest_nav_date":"2026-09-08",
                 "target_base_date":"2026-09-09","target_end_date":"2026-10-15","read_at":"2026-09-10T08:00:00Z",
                 "input_hash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                 "source_revision_id":"0c0e06a9-725e-4b68-b813-de6ff5124b29","reason_codes":[],"message":"人工响应",
                 "models":[
                  {"branch":"DROP_60D_GROUP_L2","score":0.6,"direction":"UP","fit_end":"2024-03-31",
                   "model_hash":"b08efdc7bc8fc04833d0c3d6ed1edcbd7c3caff080e4787db837cef04f17660f"},
                  {"branch":"REFERENCE","score":0.4,"direction":"NON_UP","fit_end":"2024-03-31",
                   "model_hash":"0146d0ff0ac0504d23a428c0f607d16636e457110ac67c6e60d1b7b7dea19789"}]}
                """;
    }

    private DirectionExperimentResponse exchange(String body) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/predictions/008888/experiment", request -> {
            assertEquals("synthetic-token", request.getRequestHeaders().getFirst("X-Service-Token"));
            assertNotNull(request.getRequestHeaders().getFirst("X-Trace-Id"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            request.getResponseHeaders().set("Content-Type", "application/json");
            request.sendResponseHeaders(200, bytes.length);
            request.getResponseBody().write(bytes);
            request.close();
        });
        server.start();
        try {
            var properties = new AiServiceProperties();
            properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.setToken("synthetic-token");
            return new AiPredictionClient(RestClient.builder(), properties).readExperiment("008888");
        } finally { server.stop(0); }
    }

    @Test void actualHttpMapsExperimentalScoresAndDisagreement() throws Exception {
        var result = exchange(body());
        assertEquals("EXPERIMENTAL", result.status());
        assertFalse(result.modelReleased());
        assertEquals(2, result.models().size());
        assertEquals(0.6, result.models().get(0).score());
        assertEquals("NON_UP", result.models().get(1).direction());
        assertEquals("2026-10-15", result.targetEndDate().toString());
    }

    @Test void wrongFundForgedReleaseDatesAndScoresFailClosed() throws Exception {
        for (String invalid : List.of(
                body().replace("\"fund_code\":\"008888\"", "\"fund_code\":\"001632\""),
                body().replace("\"model_released\":false", "\"model_released\":true"),
                body().replace("EXPERIMENTAL", "AVAILABLE"),
                body().replace("EXPERIMENTAL", "UNAVAILABLE"),
                body().replace("0.6", "1.2"), body().replace("0.6", "-0.1"),
                body().replace("2026-10-15", "2026-09-09"), body().replace("2024-03-31", "2026-09-09"),
                body().replace("b08efdc7", "00000000"))) {
            var result = exchange(invalid);
            assertEquals("UNAVAILABLE", result.status());
            assertTrue(result.models().isEmpty());
            assertFalse(result.modelReleased());
        }
    }
}
