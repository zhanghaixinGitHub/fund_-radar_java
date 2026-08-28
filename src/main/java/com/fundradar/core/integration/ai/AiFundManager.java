package com.fundradar.core.integration.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;

/** Python 内部服务返回的基金经理任职资料，不包含简历原文。 */
public record AiFundManager(
        @JsonProperty("manager_name") String managerName,
        @JsonProperty("ann_date") LocalDate annDate,
        @JsonProperty("begin_date") LocalDate beginDate,
        @JsonProperty("end_date") LocalDate endDate,
        String education,
        @JsonProperty("data_source") String dataSource
) {
}
