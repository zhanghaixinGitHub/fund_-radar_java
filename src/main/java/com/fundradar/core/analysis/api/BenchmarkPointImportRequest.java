package com.fundradar.core.analysis.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 限量批量导入已授权基准日序列；Python 会按代码和日期幂等写入。 */
public record BenchmarkPointImportRequest(
        @NotEmpty(message = "至少需要一条基准日序列。")
        @Size(max = 10000, message = "单次基准导入不能超过 10000 条。")
        List<@Valid BenchmarkNavPointRequest> points
) {
}
