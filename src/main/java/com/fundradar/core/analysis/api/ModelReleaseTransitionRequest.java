package com.fundradar.core.analysis.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 模型激活或暂停必须具有人工审核理由，以供审计追踪。 */
public record ModelReleaseTransitionRequest(
        @NotBlank(message = "发布操作原因不能为空。")
        @Size(max = 400, message = "发布操作原因不能超过 400 个字符。") String reason
) {
}
