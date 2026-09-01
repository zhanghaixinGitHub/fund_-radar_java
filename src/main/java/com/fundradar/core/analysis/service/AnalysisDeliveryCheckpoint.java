package com.fundradar.core.analysis.service;

import java.time.Instant;
import java.util.UUID;

/** Java 本地消费游标；两个字段必须同时为空或同时存在。 */
record AnalysisDeliveryCheckpoint(Instant lastScoredAt, UUID lastForecastId) {
}
