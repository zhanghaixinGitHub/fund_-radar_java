package com.fundradar.core.analysis.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 系统管理员登记候选回测基准的最小元数据；来源启用仍由 Python 数据治理校验。 */
public record BenchmarkRegistrationRequest(
        @NotBlank(message = "基准名称不能为空。")
        @Size(max = 128, message = "基准名称不能超过 128 个字符。") String displayName,
        @NotBlank(message = "来源编码不能为空。")
        @Size(max = 64, message = "来源编码不能超过 64 个字符。") String sourceCode,
        @NotBlank(message = "授权依据不能为空。")
        @Size(max = 512, message = "授权依据不能超过 512 个字符。") String licenseReference
) {
}
