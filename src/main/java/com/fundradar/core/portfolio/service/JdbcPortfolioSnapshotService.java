package com.fundradar.core.portfolio.service;

import com.fundradar.core.auth.CurrentUserContext;
import com.fundradar.core.auth.PermissionCode;
import com.fundradar.core.portfolio.api.PortfolioHoldingResponse;
import com.fundradar.core.portfolio.api.PortfolioSnapshotResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 基于 JDBC 的当前认证用户持仓快照只读实现。 */
@Service
public class JdbcPortfolioSnapshotService implements PortfolioSnapshotService {

    private final JdbcClient jdbcClient;

    public JdbcPortfolioSnapshotService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    /** 按当前认证用户和导入时间读取最新快照；不调用外部数据源。 */
    public PortfolioSnapshotResponse getCurrentUserSnapshot() {
        UUID userId = CurrentUserContext.requirePermission(PermissionCode.PORTFOLIO_SELF_READ).userId();
        return getSnapshot(userId);
    }

    @Override
    /** 按指定用户和导入时间读取最新快照；权限在服务层再次校验，防止被其他调用方绕过。 */
    public PortfolioSnapshotResponse getUserSnapshot(UUID userId) {
        CurrentUserContext.requirePermission(PermissionCode.PORTFOLIO_USER_READ);
        return getSnapshot(userId);
    }

    private PortfolioSnapshotResponse getSnapshot(UUID userId) {
        SnapshotRow snapshot = jdbcClient.sql("""
                        SELECT snapshot_id, source_kind, data_as_of_status, data_as_of_date, imported_at
                        FROM portfolio_snapshot
                        WHERE user_id = :userId
                        ORDER BY imported_at DESC, snapshot_id DESC
                        LIMIT 1
                        """)
                .param("userId", userId)
                .query((row, rowNumber) -> new SnapshotRow(
                        row.getObject("snapshot_id", UUID.class),
                        row.getString("source_kind"),
                        row.getString("data_as_of_status"),
                        row.getObject("data_as_of_date", LocalDate.class),
                        row.getTimestamp("imported_at").toInstant()
                ))
                .optional()
                .orElse(null);
        if (snapshot == null) {
            return PortfolioSnapshotResponse.unavailable();
        }
        List<PortfolioHoldingResponse> holdings = jdbcClient.sql("""
                        SELECT fund_code, fund_name, reported_amount, reported_weight_pct,
                               reported_daily_gain_amount, reported_holding_gain_amount,
                               reported_holding_gain_pct, reported_cumulative_gain_amount
                        FROM portfolio_holding_snapshot
                        WHERE snapshot_id = :snapshotId
                        ORDER BY reported_amount DESC, fund_code ASC
                        """)
                .param("snapshotId", snapshot.snapshotId())
                .query((row, rowNumber) -> new PortfolioHoldingResponse(
                        row.getString("fund_code"),
                        row.getString("fund_name"),
                        row.getBigDecimal("reported_amount"),
                        row.getBigDecimal("reported_weight_pct"),
                        row.getBigDecimal("reported_daily_gain_amount"),
                        row.getBigDecimal("reported_holding_gain_amount"),
                        row.getBigDecimal("reported_holding_gain_pct"),
                        row.getBigDecimal("reported_cumulative_gain_amount")
                ))
                .list();
        return new PortfolioSnapshotResponse(
                true,
                snapshot.sourceKind(),
                snapshot.dataAsOfStatus(),
                snapshot.dataAsOfDate(),
                snapshot.importedAt(),
                holdings
        );
    }

    /** 承载单个快照主记录，避免查询层向外泄露持久化细节。 */
    private record SnapshotRow(
            UUID snapshotId,
            String sourceKind,
            String dataAsOfStatus,
            LocalDate dataAsOfDate,
            Instant importedAt
    ) {
    }
}
