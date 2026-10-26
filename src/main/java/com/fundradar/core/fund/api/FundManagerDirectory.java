package com.fundradar.core.fund.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 经理公共资料契约。人数、产品数和收益缺项保持null；收益为小数比例，不含个人账户信息。 */
public final class FundManagerDirectory {
    private FundManagerDirectory() { }

    public record Coverage(String scopeLabel, boolean sourceScanComplete, boolean identityComplete,
            boolean productMappingComplete, int pendingShareCount, Instant lastSuccessfulCheckAt, String notice) { }
    public record Source(String title, String url, LocalDate publishedDate) { }
    public record Performance(String status, BigDecimal returnRate, BigDecimal maxDrawdownRate,
            BigDecimal annualizedRate, LocalDate startDate, LocalDate endDate,
            boolean includesCoManagement, String calculationBasis, String reason) { }
    public record Summary(UUID managerId, String managerName, UUID snapshotId, List<String> companies,
            String education, Integer shareCount, Integer productCount, Integer historyCount,
            String anchorFundCode, String anchorFundName, Instant checkedAt) { }
    public record Fund(UUID relatedFundId, UUID tenureVersionId, String fundCode, String fundName,
            String fundType, String companyName, String mappedFundCode, String state,
            LocalDate beginDate, LocalDate endDate, String teamStatus, Performance performance, String sourceUrl,
            Performance soloPerformance) { }
    public record Detail(UUID managerId, String managerName, UUID snapshotId, List<String> companies,
            String education, Integer shareCount, Integer productCount, Integer historyCount,
            String anchorFundCode, String anchorFundName, Instant checkedAt, Coverage coverage,
            String professionalResume, LocalDate profilePublishedOn, List<Source> sources,
            List<Source> disclosures, List<Fund> funds, boolean stale) {
        /** 只改变降级标记，保留真实资料日期，不能将缓存取得时间写成资料更新日。 */
        public Detail asStale() {
            return new Detail(managerId, managerName, snapshotId, companies, education, shareCount,
                    productCount, historyCount, anchorFundCode, anchorFundName, checkedAt, coverage,
                    professionalResume, profilePublishedOn, sources, disclosures, funds, true);
        }
    }
    public record Page(List<Summary> items, int page, int pageSize, int totalCount, int totalPages,
            UUID snapshotId, String notice, Boolean stale) {
        public Page asStale() { return new Page(items, page, pageSize, totalCount, totalPages, snapshotId, notice, true); }
    }
    public record FundPage(List<Fund> items, int page, int pageSize, int totalCount, int totalPages, UUID snapshotId) { }
    public record RelatedDetail(Detail manager, Fund fund, List<Fund> tenures) { }
    public record SyncRequest(@Size(max=20) List<@NotNull UUID> managerIds, @NotNull UUID requestId) { }
}
