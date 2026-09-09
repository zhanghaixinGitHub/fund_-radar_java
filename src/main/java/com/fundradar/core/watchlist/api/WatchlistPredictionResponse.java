package com.fundradar.core.watchlist.api;

import com.fundradar.core.integration.ai.AiWatchlistPrediction;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 仅本人关注详情可见的预测状态。
 *
 * @param fundCode 当前已授权的基金代码
 * @param status AVAILABLE可展示；其余状态不含预测数字
 * @param horizonTradingDays 未来20交易日整体方向，不是每天一条
 * @param upProbability 未正式发布必须为null，禁止填0或50%
 * @param direction 未正式发布必须为null
 * @param latestNavDate 当前来源最新已公告净值日，不是预测截止日
 * @param researchRunId 状态所依据的研究编号，可为空
 * @param researchEvaluatedAt 研究记录保存时刻，不是净值更新时间
 * @param modelVersion 研究或结果契约版本，版本名自身不等于已发布
 * @param reasonCodes 稳定的不可用原因代码
 * @param reasons 通俗中文原因
 * @param message 主状态说明
 * @param disclaimer 风险边界文案
 * @param forecastId 原预测结果编号，不是研究编号
 * @param cutoffDate 原信息截止日，不随查询变成今天
 * @param targetBaseDate 原20交易日回报的基准日期
 * @param targetEndDate 原预测区间终点，不因刷新延期
 * @param generatedAt 结果实际生成保存的时间
 * @param modelHash 实际模型内容指纹，不含系数或训练答案
 */
public record WatchlistPredictionResponse(
        String fundCode, String status, int horizonTradingDays,
        BigDecimal upProbability, String direction, LocalDate latestNavDate,
        UUID researchRunId, Instant researchEvaluatedAt, String modelVersion,
        List<String> reasonCodes, List<String> reasons, String message, String disclaimer,
        UUID forecastId, LocalDate cutoffDate, LocalDate targetBaseDate, LocalDate targetEndDate,
        Instant generatedAt, String modelHash
) {
    /** 不信任只写AVAILABLE的上游数据；校验身份、日期、数值和版本后才投影到页面。 */
    public static WatchlistPredictionResponse from(AiWatchlistPrediction source, String requestedFund) {
        if (source == null || !requestedFund.equals(source.fundCode())
                || source.horizonTradingDays() == null || source.horizonTradingDays() != 20
                || source.status() == null
                || !List.of("AVAILABLE", "STALE", "MODEL_NOT_RELEASED", "DATA_INSUFFICIENT", "NOT_APPLICABLE", "UNAVAILABLE").contains(source.status())
                || source.reasonCodes() == null || source.reasonCodes().size() > 12
                || source.reasons() == null || source.reasons().size() != source.reasonCodes().size()
                || source.message() == null || source.message().length() > 200
                || source.disclaimer() == null || source.disclaimer().length() > 300
                || source.reasons().stream().anyMatch(value -> value == null || value.length() > 300)
                || source.reasonCodes().stream().anyMatch(value -> value == null || value.length() > 150)
                || source.modelVersion() != null && !List.of("CASH_RESEARCH_PROTOCOL_V1", "CASH_FORECAST_STORAGE_V1").contains(source.modelVersion())) {
            throw new IllegalArgumentException("Invalid cash prediction state");
        }
        boolean available = "AVAILABLE".equals(source.status());
        if (available) {
            if (!"CASH_FORECAST_STORAGE_V1".equals(source.modelVersion())
                    || source.forecastId() == null || source.researchRunId() == null
                    || source.cutoffDate() == null || source.targetBaseDate() == null || source.targetEndDate() == null
                    || source.targetBaseDate().isAfter(source.cutoffDate()) || !source.targetEndDate().isAfter(source.cutoffDate())
                    || source.generatedAt() == null || source.modelHash() == null || !source.modelHash().matches("[0-9a-f]{64}")
                    || source.upProbability() == null || source.upProbability().compareTo(BigDecimal.ZERO) < 0
                    || source.upProbability().compareTo(BigDecimal.ONE) > 0 || !source.reasonCodes().isEmpty()
                    || !(source.upProbability().compareTo(new BigDecimal("0.5")) > 0 ? "UP" : "NON_UP").equals(source.direction())) {
                throw new IllegalArgumentException("Invalid available cash forecast");
            }
        } else if (source.upProbability() != null || source.direction() != null || source.reasonCodes().isEmpty()) {
            throw new IllegalArgumentException("Unavailable cash state contains numbers or lacks reasons");
        }
        return new WatchlistPredictionResponse(requestedFund, source.status(), 20, source.upProbability(), source.direction(),
                source.latestNavDate(), source.researchRunId(), source.researchEvaluatedAt(), source.modelVersion(),
                List.copyOf(source.reasonCodes()), List.copyOf(source.reasons()), source.message(), source.disclaimer(),
                source.forecastId(), source.cutoffDate(), source.targetBaseDate(), source.targetEndDate(),
                source.generatedAt(), source.modelHash());
    }

    /** 失败只影响预测卡片，不伪造基金趋势或沿用另一个用户的缓存。 */
    public static WatchlistPredictionResponse unavailable(String fundCode) {
        return new WatchlistPredictionResponse(fundCode, "UNAVAILABLE", 20, null, null, null, null, null, null,
                List.of("PREDICTION_SERVICE_UNAVAILABLE"), List.of("预测服务暂时无法读取，请稍后刷新；其他基金资料不受影响。"),
                "预测状态暂时无法加载。", "仅供研究参考，不构成投资建议；无法读取不表示基金会下跌。",
                null, null, null, null, null, null);
    }
}
