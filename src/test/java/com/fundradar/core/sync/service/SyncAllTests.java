package com.fundradar.core.sync.service;

import com.fundradar.core.auth.AccountRole;
import com.fundradar.core.auth.AuthenticatedUser;
import com.fundradar.core.auth.CurrentUserContext;
import com.fundradar.core.auth.PermissionCode;
import com.fundradar.core.common.web.GlobalExceptionHandler;
import com.fundradar.core.integration.ai.AiServiceProperties;
import com.fundradar.core.integration.ai.AiSyncJobClient;
import com.fundradar.core.sync.controller.SyncJobController;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 本地 HTTP 替身验证公开路由、权限、内部转发和冲突；不启动数据库或真实同步。 */
class SyncAllTests {
    private HttpServer server;
    private MockMvc mvc;
    private final List<String> calls = new ArrayList<>();
    private final List<String> tokens = new ArrayList<>();
    private int upstreamStatus = 200;
    private String upstreamBody = """
            {"job_id":"00000000-0000-0000-0000-000000000501","job_type":"MARKET_ALL",
             "status":"PARTIAL_SUCCESS","requested_nav_date":"2026-09-10","fund_codes":[],
             "progress_current":4,"progress_total":4,"current_fund_code":null,
             "progress_message":"成功 3 项，未完成 1 项","sync_run_id":null,
             "fetched_count":0,"created_count":0,"updated_count":0,"skipped_count":0,
             "error_code":"SYNC_ALL_INCOMPLETE","error_message":"未完成：免费数据补齐",
             "started_at":"2026-09-10T01:00:00Z","finished_at":"2026-09-10T01:30:00Z"}
            """;

    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/funds/sync-jobs", exchange -> {
            calls.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            tokens.add(exchange.getRequestHeaders().getFirst("X-Service-Token"));
            byte[] body = upstreamBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(upstreamStatus, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        AiServiceProperties properties = new AiServiceProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setToken("sync-test-only-token");
        var service = new InternalSyncJobService(new AiSyncJobClient(RestClient.builder(), properties));
        mvc = MockMvcBuilders.standaloneSetup(new SyncJobController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @AfterEach
    void cleanup() {
        CurrentUserContext.clear();
        server.stop(0);
    }

    private void login(PermissionCode... permissions) {
        CurrentUserContext.set(new AuthenticatedUser(UUID.randomUUID(), "test", "测试用户",
                AccountRole.DATA_OPERATOR, Set.of(permissions)));
    }

    @Test
    void rejectsMissingIdentityAndPermissionsBeforeAnyInternalRequest() throws Exception {
        mvc.perform(post("/api/v1/sync-jobs/all")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/sync-jobs/all/latest")).andExpect(status().isUnauthorized());
        login(PermissionCode.SYNC_JOB_READ);
        mvc.perform(post("/api/v1/sync-jobs/all")).andExpect(status().isForbidden());
        login(PermissionCode.SYNC_JOB_START);
        mvc.perform(get("/api/v1/sync-jobs/all/latest")).andExpect(status().isForbidden());
        assertTrue(calls.isEmpty());
    }

    @Test
    void forwardsBatchAndLatestAndPreservesPartialSuccess() throws Exception {
        login(PermissionCode.SYNC_JOB_START, PermissionCode.SYNC_JOB_READ);
        String response = mvc.perform(post("/api/v1/sync-jobs/all"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.jobType").value("MARKET_ALL"))
                .andExpect(jsonPath("$.data.status").value("PARTIAL_SUCCESS"))
                .andExpect(jsonPath("$.data.progressCurrent").value(4))
                .andExpect(jsonPath("$.data.errorCode").value("SYNC_ALL_INCOMPLETE"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(response.contains("sync-test-only-token"));
        mvc.perform(get("/api/v1/sync-jobs/all/latest")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobId").value("00000000-0000-0000-0000-000000000501"));
        assertEquals(List.of("POST /internal/v1/funds/sync-jobs/all",
                "GET /internal/v1/funds/sync-jobs/all/latest"), calls);
        assertEquals(List.of("sync-test-only-token", "sync-test-only-token"), tokens);
    }

    @Test
    void conflictIsForwardedWithoutAutomaticallyRetryingThePost() throws Exception {
        login(PermissionCode.SYNC_JOB_START);
        upstreamStatus = 409;
        upstreamBody = "{}";
        mvc.perform(post("/api/v1/sync-jobs/all")).andExpect(status().isConflict());
        assertEquals(1, calls.size());
    }

    @Test
    void missingBatchAfterRestartIsReturnedAsNull() throws Exception {
        login(PermissionCode.SYNC_JOB_READ);
        upstreamBody = "null";
        mvc.perform(get("/api/v1/sync-jobs/all/latest")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());
    }
}
