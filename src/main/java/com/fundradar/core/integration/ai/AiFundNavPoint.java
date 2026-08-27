package com.fundradar.core.integration.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Python 内部服务返回的一条已落库历史净值。 */
public record AiFundNavPoint(
        @JsonProperty("nav_date") LocalDate navDate,
        @JsonProperty("unit_nav") BigDecimal unitNav,
        @JsonProperty("accumulated_nav") BigDecimal accumulatedNav
) {
}
