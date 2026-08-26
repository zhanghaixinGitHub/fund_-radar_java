package com.fundradar.core.portfolio.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 当前本机用户可查看的最新持仓快照；无数据时只返回可识别的空状态。 */
public record PortfolioSnapshotResponse(
        boolean available,
        String sourceKind,
        String dataAsOfStatus,
        LocalDate dataAsOfDate,
        Instant importedAt,
        List<PortfolioHoldingResponse> holdings
) {

    /** 构造无快照时的稳定响应，避免用示例或实时数据伪造替代。 */
    public static PortfolioSnapshotResponse unavailable() {
        return new PortfolioSnapshotResponse(false, null, null, null, null, List.of());
    }
}
