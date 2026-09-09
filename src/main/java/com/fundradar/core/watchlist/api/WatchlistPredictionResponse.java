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
 * @param status 未发布、资料不足、不适用或暂不可用；不是涨跌结论
 * @param horizonTradingDays 未来20交易日整体方向，不是每天一条
 * @param upProbability 未正式发布必须为null，禁止填0或50%
 * @param direction 未正式发布必须为null
 * @param latestNavDate 当前来源最新已公告净值日，不是预测截止日
 * @param researchRunId 状态所依据的研究编号，可为空
 * @param researchEvaluatedAt 研究记录保存时刻，不是净值更新时间
 * @param modelVersion 研究协议版本，不能解释为已发布
 * @param reasonCodes 稳定的不可用原因代码
 * @param reasons 通俗中文原因
 * @param message 主状态说明
 * @param disclaimer 风险边界文案
 */
public record WatchlistPredictionResponse(
        String fundCode, String status, int horizonTradingDays,
        BigDecimal upProbability, String direction, LocalDate latestNavDate,
        UUID researchRunId, Instant researchEvaluatedAt, String modelVersion,
        List<String> reasonCodes, List<String> reasons, String message, String disclaimer
) {
    /** Python当前协议只允许无概率响应；跨服务契约漂移时拒绝展示。 */
    public static WatchlistPredictionResponse from(AiWatchlistPrediction source, String requestedFund) {
        if (source == null || !requestedFund.equals(source.fundCode())
                || source.horizonTradingDays() == null || source.horizonTradingDays() != 20
                || source.status() == null
                || !List.of("MODEL_NOT_RELEASED", "DATA_INSUFFICIENT", "NOT_APPLICABLE", "UNAVAILABLE").contains(source.status())
                || source.upProbability() != null || source.direction() != null
                || source.reasonCodes() == null || source.reasonCodes().isEmpty() || source.reasonCodes().size() > 12
                || source.reasons() == null || source.reasons().isEmpty() || source.reasons().size() > 12
                || source.message() == null || source.message().length() > 200
                || source.disclaimer() == null || source.disclaimer().length() > 300
                || source.reasons().stream().anyMatch(value -> value == null || value.length() > 300)
                || source.reasonCodes().stream().anyMatch(value -> value == null || value.length() > 150)
                || source.modelVersion() != null && !"CASH_RESEARCH_PROTOCOL_V1".equals(source.modelVersion())) {
            throw new IllegalArgumentException("Invalid cash prediction state");
        }
        return new WatchlistPredictionResponse(requestedFund, source.status(), 20, null, null,
                source.latestNavDate(), source.researchRunId(), source.researchEvaluatedAt(), source.modelVersion(),
                List.copyOf(source.reasonCodes()), List.copyOf(source.reasons()), source.message(), source.disclaimer());
    }

    /** 失败只影响预测卡片，不伪造基金趋势或沿用另一个用户的缓存。 */
    public static WatchlistPredictionResponse unavailable(String fundCode) {
        return new WatchlistPredictionResponse(fundCode, "UNAVAILABLE", 20, null, null, null, null, null, null,
                List.of("PREDICTION_SERVICE_UNAVAILABLE"), List.of("预测服务暂时无法读取，请稍后刷新；其他基金资料不受影响。"),
                "预测状态暂时无法加载。", "仅供研究参考，不构成投资建议；无法读取不表示基金会下跌。");
    }
}
