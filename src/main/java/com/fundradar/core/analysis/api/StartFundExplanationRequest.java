package com.fundradar.core.analysis.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** 管理员请求生成已发布评分解释的最小参数；不接收自由提示词或模型名称。 */
public record StartFundExplanationRequest(
        @NotBlank(message = "基金代码不能为空。")
        @Pattern(regexp = "^\\d{6}$", message = "基金代码必须为六位数字。")
        String fundCode
) {
}
