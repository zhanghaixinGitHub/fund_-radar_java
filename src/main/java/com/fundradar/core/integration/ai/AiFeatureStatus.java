package com.fundradar.core.integration.ai;

/** Python AI 内部服务返回的 M3-G1 特征可用状态。 */
public record AiFeatureStatus(
        String status,
        AiFeatureSnapshot snapshot
) {
}
