package com.fundradar.core.integration.ai;

import java.time.Instant;

public record AiHealthStatus(
        String status,
        String service,
        Instant checkedAt,
        String detail
) {
}
