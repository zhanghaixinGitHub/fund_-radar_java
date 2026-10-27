package com.fundradar.core.fund.api;

/** 本次比较组的名称、实际准入条件和口径标记；页面仅呈现名称与业务说明。 */
public record FundComparisonGroupResponse(String name, String description, String basis) {
}
