package com.fundradar.core.integration.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Python 自行计算的具体任期回报；returnRate 为小数比例，未知为空，日期为实际净值端点。 */
public record AiFundManagerPerformance(
        String status,
        @JsonProperty("return_rate") BigDecimal returnRate,
        @JsonProperty("start_date") LocalDate startDate,
        @JsonProperty("end_date") LocalDate endDate,
        String reason,
        @JsonProperty("includes_co_management") boolean includesCoManagement,
        @JsonProperty("calculation_basis") String calculationBasis
) {
}
