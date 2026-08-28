package com.fundradar.core.integration.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Python AI 内部服务返回的只读基金详情。 */
public record AiFundDetail(
        @JsonProperty("fund_code") String fundCode,
        @JsonProperty("fund_name") String fundName,
        @JsonProperty("fund_type") String fundType,
        String status,
        @JsonProperty("as_of_date") LocalDate asOfDate,
        @JsonProperty("unit_nav") BigDecimal unitNav,
        @JsonProperty("accumulated_nav") BigDecimal accumulatedNav,
        @JsonProperty("nav_ann_date") LocalDate navAnnDate,
        @JsonProperty("accumulated_dividend") BigDecimal accumulatedDividend,
        @JsonProperty("net_asset") BigDecimal netAsset,
        @JsonProperty("total_net_asset") BigDecimal totalNetAsset,
        @JsonProperty("adjusted_nav") BigDecimal adjustedNav,
        @JsonProperty("nav_status") String navStatus,
        @JsonProperty("data_source") String dataSource,
        @JsonProperty("day_change_rate") BigDecimal dayChangeRate,
        @JsonProperty("week_change_rate") BigDecimal weekChangeRate,
        @JsonProperty("month_change_rate") BigDecimal monthChangeRate,
        @JsonProperty("profile_status") String profileStatus,
        @JsonProperty("profile_data_source") String profileDataSource,
        @JsonProperty("management_company_name") String managementCompanyName,
        @JsonProperty("custodian_name") String custodianName,
        @JsonProperty("found_date") LocalDate foundDate,
        @JsonProperty("due_date") LocalDate dueDate,
        @JsonProperty("list_date") LocalDate listDate,
        @JsonProperty("issue_date") LocalDate issueDate,
        @JsonProperty("delist_date") LocalDate delistDate,
        @JsonProperty("issue_amount") BigDecimal issueAmount,
        @JsonProperty("management_fee") BigDecimal managementFee,
        @JsonProperty("custodian_fee") BigDecimal custodianFee,
        @JsonProperty("duration_year") BigDecimal durationYear,
        @JsonProperty("par_value") BigDecimal parValue,
        @JsonProperty("min_purchase_amount") BigDecimal minPurchaseAmount,
        @JsonProperty("expected_return") BigDecimal expectedReturn,
        String benchmark,
        @JsonProperty("invest_type") String investType,
        @JsonProperty("source_fund_type") String sourceFundType,
        @JsonProperty("trustee_name") String trusteeName,
        @JsonProperty("purchase_start_date") LocalDate purchaseStartDate,
        @JsonProperty("redemption_start_date") LocalDate redemptionStartDate,
        String market
) {

    /** 为既有内部客户端测试和旧数据构造保留兼容构造函数。 */
    public AiFundDetail(
            String fundCode,
            String fundName,
            String fundType,
            String status,
            LocalDate asOfDate,
            BigDecimal unitNav,
            BigDecimal accumulatedNav,
            String navStatus,
            String dataSource,
            BigDecimal dayChangeRate,
            BigDecimal weekChangeRate,
            BigDecimal monthChangeRate
    ) {
        this(
                fundCode, fundName, fundType, status, asOfDate, unitNav, accumulatedNav,
                null, null, null, null, null,
                navStatus, dataSource, dayChangeRate, weekChangeRate, monthChangeRate,
                "NOT_SYNCED", null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null
        );
    }
}
