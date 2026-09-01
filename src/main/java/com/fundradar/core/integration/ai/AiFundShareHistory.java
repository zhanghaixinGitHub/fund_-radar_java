package com.fundradar.core.integration.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Python 内部服务返回的关注后基金份额规模历史。 */
public record AiFundShareHistory(
        @JsonProperty("fund_code") String fundCode,
        String status,
        List<AiFundShareSnapshot> items
) {
}
