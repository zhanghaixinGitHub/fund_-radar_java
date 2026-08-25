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
 * M0 系统边界健康检查接口。
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

    /** 返回 Java 核心服务自身可用状态，供浏览器页面的基础连通性检查使用。 */
    @GetMapping("/health")
    public ApiResponse<CoreHealthResponse> getCoreHealth() {
        LOGGER.info("SystemHealthController.getCoreHealth   >>> core health requested");
        return ApiResponse.success(new CoreHealthResponse("fund-core", "UP", Instant.now()));
    }

    /** 探测 Python AI 内部服务的可达性，并将探测结果转换为安全的公开状态。 */
    @GetMapping("/system/ai-health")
    public ApiResponse<AiHealthStatus> getAiHealth() {
        LOGGER.info("SystemHealthController.getAiHealth   >>> AI health requested");
        return ApiResponse.success(aiHealthClient.checkHealth());
    }

    /** Java 核心服务自身健康状态的最小公开响应。 */
    public record CoreHealthResponse(String service, String status, Instant time) {
    }
}
