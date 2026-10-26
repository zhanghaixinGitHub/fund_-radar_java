package com.fundradar.core.integration.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Python 内部服务返回的完整基金资料，不包含用户或关注关系。 */
public record AiFundWatchlistDetail(
        AiFundDetail basic,
        @JsonProperty("managers_status") String managersStatus,
        List<AiFundManager> managers,
        @JsonProperty("latest_share_status") String latestShareStatus,
        @JsonProperty("latest_share") AiFundShareSnapshot latestShare,
        @JsonProperty("dividends_status") String dividendsStatus,
        List<AiFundDividend> dividends,
        @JsonProperty("manager_periods") List<AiFundManagerPeriod> managerPeriods
) {
    /** 兼容未提供阶段回报的旧调用方，阶段缺失不能用完整任期回报代替。 */
    public AiFundWatchlistDetail(AiFundDetail basic, String managersStatus, List<AiFundManager> managers,
            String latestShareStatus, AiFundShareSnapshot latestShare, String dividendsStatus,
            List<AiFundDividend> dividends) {
        this(basic, managersStatus, managers, latestShareStatus, latestShare, dividendsStatus, dividends, List.of());
    }
}
