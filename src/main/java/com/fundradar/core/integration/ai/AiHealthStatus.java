package com.fundradar.core.integration.ai;

import java.time.Instant;

/** Java 核心服务对外暴露的 Python AI 服务健康探测结果。 */
public record AiHealthStatus(
        String status,
        String service,
        Instant checkedAt,
        String detail
) {
}
