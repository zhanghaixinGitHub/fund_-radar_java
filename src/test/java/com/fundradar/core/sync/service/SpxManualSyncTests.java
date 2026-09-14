package com.fundradar.core.sync.service;

import com.fundradar.core.auth.*;
import com.fundradar.core.common.web.GlobalExceptionHandler;
import com.fundradar.core.integration.ai.AiServiceProperties;
import com.fundradar.core.integration.ai.AiSpxManualClient;
import com.fundradar.core.sync.controller.SpxManualSyncController;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 本地HTTP替身检查权限、一次转发及安全响应；不连接真实Tushare，不改用户或模型。 */
class SpxManualSyncTests {
    private HttpServer server;
    private MockMvc mvc;
    private final List<String> calls = new ArrayList<>();
    private int responseStatus = 200;

    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/spx-manual", exchange -> {
            calls.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            assertEquals("test-only-spx-token", exchange.getRequestHeaders().getFirst("X-Service-Token"));
            byte[] body = (responseStatus == 200 ? """
                    {"mode":"MANUAL","serverTime":"2026-09-13T15:00:00+08:00",
                     "canSync":true,"message":"按需同步","attemptsToday":5,"maxAttemptsPerDay":null,"performedNow":false,
                     "availability":"READY","errorCode":null,
                     "nextAllowedAt":null,"lastAttempt":{"attemptId":"test","state":"REFERENCE_ONLY",
                     "message":"资料参考","latestUsDate":"2026-09-11","close":6000,
                     "dailyChangePct":0.5,"rowCount":2,"missingUsDates":[],"usableBeforeU08":false,
                     "stage":"DONE","stageLabel":"同步完成","completedSteps":3,"totalSteps":3,"errorCode":null}}
                    """ : "upstream-secret-must-not-leak").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        var properties = new AiServiceProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setToken("test-only-spx-token");
        mvc = MockMvcBuilders.standaloneSetup(new SpxManualSyncController(
                        new AiSpxManualClient(RestClient.builder(), properties)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @AfterEach
    void cleanup() { CurrentUserContext.clear(); server.stop(0); }

    private void user(Set<PermissionCode> permissions) {
        CurrentUserContext.set(new AuthenticatedUser(UUID.randomUUID(), "", "test", AccountRole.FUND_USER, permissions));
    }

    @Test
    void anonymousAndOrdinaryUserCannotReadOrSynchronize() throws Exception {
        mvc.perform(get("/api/v1/sync-jobs/spx-manual/status")).andExpect(status().isUnauthorized());
        user(Set.of());
        mvc.perform(get("/api/v1/sync-jobs/spx-manual/status")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/sync-jobs/spx-manual/sync")).andExpect(status().isForbidden());
        assertTrue(calls.isEmpty());
    }

    @Test
    void readPermissionDoesNotStartSync() throws Exception {
        user(Set.of(PermissionCode.SYNC_JOB_READ));
        mvc.perform(get("/api/v1/sync-jobs/spx-manual/status"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.mode").value("MANUAL"));
        mvc.perform(post("/api/v1/sync-jobs/spx-manual/sync")).andExpect(status().isForbidden());
        assertEquals(List.of("GET /internal/v1/spx-manual/status"), calls);
    }

    @Test
    void clickForwardsOnePostAndPreservesLateEligibility() throws Exception {
        user(Set.of(PermissionCode.SYNC_JOB_START));
        mvc.perform(post("/api/v1/sync-jobs/spx-manual/sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lastAttempt.state").value("REFERENCE_ONLY"))
                .andExpect(jsonPath("$.data.attemptsToday").value(5))
                .andExpect(jsonPath("$.data.maxAttemptsPerDay").isEmpty())
                .andExpect(jsonPath("$.data.availability").value("READY"))
                .andExpect(jsonPath("$.data.lastAttempt.completedSteps").value(3))
                .andExpect(jsonPath("$.data.lastAttempt.usableBeforeU08").value(false));
        assertEquals(List.of("POST /internal/v1/spx-manual/sync"), calls);
    }

    @Test
    void upstreamFailureDoesNotRetryOrExposeBody() throws Exception {
        user(Set.of(PermissionCode.SYNC_JOB_START));
        responseStatus = 500;
        var result = mvc.perform(post("/api/v1/sync-jobs/spx-manual/sync"))
                .andExpect(status().isServiceUnavailable()).andReturn();
        assertFalse(result.getResponse().getContentAsString().contains("upstream-secret"));
        assertEquals(1, calls.size());
    }
}
