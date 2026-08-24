package com.fundradar.core.system.controller;

import com.fundradar.core.common.api.ApiResponse;
import com.fundradar.core.integration.ai.AiHealthClient;
import com.fundradar.core.integration.ai.AiHealthStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * M0 system boundary endpoints.
 *
 * <p>关联文档：
 * docs_zhx/requirements/fund-radar.md；
 * docs_zhx/design/fund-radar.md；
 * docs_zhx/testcase/fund-radar.md。</p>
 */
@RestController
@RequestMapping("/api/v1")
public class SystemHealthController {

    private static final Logger LOGGER = LoggerFactory.getLogger(SystemHealthController.class);

    private final AiHealthClient aiHealthClient;

    public SystemHealthController(AiHealthClient aiHealthClient) {
        this.aiHealthClient = aiHealthClient;
    }

    @GetMapping("/health")
    public ApiResponse<CoreHealthResponse> getCoreHealth() {
        LOGGER.info("SystemHealthController.getCoreHealth   >>> core health requested");
        return ApiResponse.success(new CoreHealthResponse("fund-core", "UP", Instant.now()));
    }

    @GetMapping("/system/ai-health")
    public ApiResponse<AiHealthStatus> getAiHealth() {
        LOGGER.info("SystemHealthController.getAiHealth   >>> AI health requested");
        return ApiResponse.success(aiHealthClient.checkHealth());
    }

    public record CoreHealthResponse(String service, String status, Instant time) {
    }
}
