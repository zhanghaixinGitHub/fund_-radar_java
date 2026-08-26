package com.fundradar.core.portfolio.importer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fundradar.core.common.trace.TraceContext;
import com.fundradar.core.portfolio.service.JdbcPortfolioSnapshotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

/** 将用户确认的本机截图字段导入为可追溯、幂等的持仓快照。 */
@Service
public class LocalPortfolioSnapshotImporter {

    private static final Logger LOGGER = LoggerFactory.getLogger(LocalPortfolioSnapshotImporter.class);
    private static final String LOCAL_ACTOR = "local-user";
    private static final String USER_CONFIRMED_SCREENSHOT = "USER_CONFIRMED_SCREENSHOT";
    private static final String UNKNOWN = "UNKNOWN";
    private static final String KNOWN = "KNOWN";

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    public LocalPortfolioSnapshotImporter(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * 从被显式指定的本机文件导入一份快照。
     *
     * @param importFile 已由用户确认的本机 JSON 文件，不支持 HTTP 地址、浏览器上传或第三方账户读取
     */
    @Transactional
    public void importFromFile(Path importFile) {
        if (!Files.isRegularFile(importFile)) {
            throw new IllegalArgumentException("portfolio import file does not exist or is not a regular file");
        }
        LocalPortfolioSnapshotInput input = readInput(importFile);
        validate(input);
        UUID snapshotId = UUID.randomUUID();
        String contentHash = hashCanonicalInput(input);
        int inserted = jdbcClient.sql("""
                        INSERT INTO portfolio_snapshot (
                            snapshot_id, user_id, source_kind, data_as_of_date, data_as_of_status,
                            source_description, source_content_hash
                        ) VALUES (
                            :snapshotId, :userId, :sourceKind, :dataAsOfDate, :dataAsOfStatus,
                            :sourceDescription, :sourceContentHash
                        )
                        ON CONFLICT (user_id, source_content_hash) DO NOTHING
                        """)
                .param("snapshotId", snapshotId)
                .param("userId", JdbcPortfolioSnapshotService.LOCAL_USER_ID)
                .param("sourceKind", input.sourceKind())
                .param("dataAsOfDate", input.dataAsOfDate())
                .param("dataAsOfStatus", input.dataAsOfStatus())
                .param("sourceDescription", input.sourceDescription().trim())
                .param("sourceContentHash", contentHash)
                .update();
        UUID persistedSnapshotId = inserted == 1
                ? snapshotId
                : jdbcClient.sql("""
                                SELECT snapshot_id
                                FROM portfolio_snapshot
                                WHERE user_id = :userId AND source_content_hash = :sourceContentHash
                                """)
                        .param("userId", JdbcPortfolioSnapshotService.LOCAL_USER_ID)
                        .param("sourceContentHash", contentHash)
                        .query(UUID.class)
                        .single();
        for (LocalPortfolioSnapshotInput.LocalPortfolioHoldingInput holding : input.holdings()) {
            jdbcClient.sql("""
                            INSERT INTO portfolio_holding_snapshot (
                                holding_snapshot_id, snapshot_id, fund_code, fund_name, reported_amount,
                                reported_weight_pct, reported_daily_gain_amount, reported_holding_gain_amount,
                                reported_holding_gain_pct, reported_cumulative_gain_amount
                            ) VALUES (
                                :holdingSnapshotId, :snapshotId, :fundCode, :fundName, :reportedAmount,
                                :reportedWeightPct, :reportedDailyGainAmount, :reportedHoldingGainAmount,
                                :reportedHoldingGainPct, :reportedCumulativeGainAmount
                            )
                            ON CONFLICT (snapshot_id, fund_code) DO UPDATE SET
                                fund_name = EXCLUDED.fund_name,
                                reported_amount = EXCLUDED.reported_amount,
                                reported_weight_pct = EXCLUDED.reported_weight_pct,
                                reported_daily_gain_amount = EXCLUDED.reported_daily_gain_amount,
                                reported_holding_gain_amount = EXCLUDED.reported_holding_gain_amount,
                                reported_holding_gain_pct = EXCLUDED.reported_holding_gain_pct,
                                reported_cumulative_gain_amount = EXCLUDED.reported_cumulative_gain_amount
                            """)
                    .param("holdingSnapshotId", UUID.randomUUID())
                    .param("snapshotId", persistedSnapshotId)
                    .param("fundCode", holding.fundCode())
                    .param("fundName", holding.fundName().trim())
                    .param("reportedAmount", holding.reportedAmount())
                    .param("reportedWeightPct", holding.reportedWeightPct())
                    .param("reportedDailyGainAmount", holding.reportedDailyGainAmount())
                    .param("reportedHoldingGainAmount", holding.reportedHoldingGainAmount())
                    .param("reportedHoldingGainPct", holding.reportedHoldingGainPct())
                    .param("reportedCumulativeGainAmount", holding.reportedCumulativeGainAmount())
                    .update();
        }
        writeAudit(inserted == 1 ? "PORTFOLIO_SNAPSHOT_IMPORTED" : "PORTFOLIO_SNAPSHOT_IMPORT_IDEMPOTENT", persistedSnapshotId);
        LOGGER.info(
                "LocalPortfolioSnapshotImporter.importFromFile   >>> imported portfolio snapshot, holdings={}, inserted={}, dataAsOfStatus={}",
                input.holdings().size(), inserted == 1, input.dataAsOfStatus()
        );
    }

    /** 读取本机 JSON，不输出文件内容或持仓金额到日志。 */
    private LocalPortfolioSnapshotInput readInput(Path importFile) {
        try {
            return objectMapper.readValue(importFile.toFile(), LocalPortfolioSnapshotInput.class);
        } catch (IOException exception) {
            throw new IllegalArgumentException("portfolio import file cannot be parsed", exception);
        }
    }

    /** 校验来源、日期边界、基金代码唯一性与金额范围，拒绝任何推算字段。 */
    private void validate(LocalPortfolioSnapshotInput input) {
        if (input == null || !USER_CONFIRMED_SCREENSHOT.equals(input.sourceKind())) {
            throw new IllegalArgumentException("portfolio import sourceKind must be USER_CONFIRMED_SCREENSHOT");
        }
        if (!UNKNOWN.equals(input.dataAsOfStatus()) && !KNOWN.equals(input.dataAsOfStatus())) {
            throw new IllegalArgumentException("portfolio import dataAsOfStatus is invalid");
        }
        if ((UNKNOWN.equals(input.dataAsOfStatus()) && input.dataAsOfDate() != null)
                || (KNOWN.equals(input.dataAsOfStatus()) && input.dataAsOfDate() == null)) {
            throw new IllegalArgumentException("portfolio import date does not match dataAsOfStatus");
        }
        if (input.sourceDescription() == null || input.sourceDescription().isBlank()
                || input.sourceDescription().length() > 512) {
            throw new IllegalArgumentException("portfolio import sourceDescription is invalid");
        }
        if (input.holdings() == null || input.holdings().isEmpty() || input.holdings().size() > 100) {
            throw new IllegalArgumentException("portfolio import holdings count is invalid");
        }
        Set<String> fundCodes = new HashSet<>();
        for (LocalPortfolioSnapshotInput.LocalPortfolioHoldingInput holding : input.holdings()) {
            if (holding == null || holding.fundCode() == null || !holding.fundCode().matches("\\d{6}")
                    || holding.fundName() == null || holding.fundName().isBlank() || holding.fundName().length() > 256
                    || !fundCodes.add(holding.fundCode())) {
                throw new IllegalArgumentException("portfolio import holding identity is invalid");
            }
            validateAmount(holding.reportedAmount(), false, "reportedAmount");
            validateAmount(holding.reportedWeightPct(), false, "reportedWeightPct");
            validateAmount(holding.reportedDailyGainAmount(), true, "reportedDailyGainAmount");
            validateAmount(holding.reportedHoldingGainAmount(), true, "reportedHoldingGainAmount");
            validateAmount(holding.reportedHoldingGainPct(), true, "reportedHoldingGainPct");
            validateAmount(holding.reportedCumulativeGainAmount(), true, "reportedCumulativeGainAmount");
            if (holding.reportedWeightPct().compareTo(BigDecimal.valueOf(100)) > 0) {
                throw new IllegalArgumentException("portfolio import reportedWeightPct exceeds 100");
            }
        }
    }

    /** 校验金额字段不为空；非收益字段不得小于零。 */
    private void validateAmount(BigDecimal value, boolean allowNegative, String fieldName) {
        if (value == null || (!allowNegative && value.signum() < 0)) {
            throw new IllegalArgumentException("portfolio import " + fieldName + " is invalid");
        }
    }

    /** 对语义化 JSON 生成稳定哈希，避免重试导入产生重复快照。 */
    private String hashCanonicalInput(LocalPortfolioSnapshotInput input) {
        try {
            byte[] canonicalInput = objectMapper.writeValueAsBytes(input);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalInput));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("portfolio import hash generation failed", exception);
        }
    }

    /** 记录导入结果但不记录用户财务数字、基金列表或导入文件路径。 */
    private void writeAudit(String action, UUID snapshotId) {
        jdbcClient.sql("""
                        INSERT INTO audit_log (audit_log_id, trace_id, actor, action, target_id)
                        VALUES (:auditId, :traceId, :actor, :action, :targetId)
                        """)
                .param("auditId", UUID.randomUUID())
                .param("traceId", TraceContext.getTraceId())
                .param("actor", LOCAL_ACTOR)
                .param("action", action)
                .param("targetId", snapshotId.toString())
                .update();
    }
}
