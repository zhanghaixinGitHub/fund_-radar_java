package com.fundradar.core.analysis.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** 一条已人工核验的基准日收盘值；不接收浏览器指定的来源或模型信息。 */
public record BenchmarkNavPointRequest(
        @NotNull(message = "基准日期不能为空。") LocalDate navDate,
        @NotNull(message = "基准收盘值不能为空。")
        @DecimalMin(value = "0.00000001", message = "基准收盘值必须大于 0。") BigDecimal closingValue,
        Instant sourcePublishedAt
) {
}
