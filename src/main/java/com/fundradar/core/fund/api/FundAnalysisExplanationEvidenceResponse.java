package com.fundradar.core.fund.api;

/** 对外披露的一条已验证解释证据，不包含模型输入或原始外部响应。 */
public record FundAnalysisExplanationEvidenceResponse(String label, String detail) {
}
