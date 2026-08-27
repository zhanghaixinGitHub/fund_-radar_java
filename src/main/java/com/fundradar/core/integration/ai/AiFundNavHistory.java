package com.fundradar.core.integration.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Python 内部服务返回的指定基金历史净值读模型。 */
public record AiFundNavHistory(
        @JsonProperty("fund_code") String fundCode,
        List<AiFundNavPoint> items
) {
}
