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
        List<AiFundDividend> dividends
) {
}
