package com.fundradar.core.integration.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.util.List;

/** Python 切分的团队阶段；起止日为展示日期，performance内日期为实际净值端点。 */
public record AiFundManagerPeriod(
        @JsonProperty("begin_date") LocalDate beginDate,
        @JsonProperty("end_date") LocalDate endDate,
        @JsonProperty("manager_names") List<String> managerNames,
        AiFundManagerPerformance performance
) {
}
