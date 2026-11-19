package com.fundradar.core.notification.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fundradar.core.advice.RuleRepository;
import com.fundradar.core.integration.ai.AiFundNavHistory;
import com.fundradar.core.integration.ai.AiFundNavPoint;
import com.fundradar.core.integration.ai.AiSignalSummary;
import com.fundradar.core.simulation.SimulationRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.*;
import java.util.*;
import java.util.function.Function;

import static com.fundradar.core.notification.service.ReviewNoticeService.*;

@Service
public class RuleReminderChecker {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final int DRAWDOWN_POINTS = 63;
    private static final Map<String, BigDecimal> RISK = Map.of(
            "LOW", new BigDecimal("0.25"), "MEDIUM", new BigDecimal("0.50"), "HIGH", BigDecimal.ONE);
    private final JdbcClient db;
    private final RuleRepository rules;
    private final SimulationRepository positions;
    private final ReviewNoticeService notices;

    public RuleReminderChecker(JdbcClient db, RuleRepository rules, SimulationRepository positions,
                               ReviewNoticeService notices) {
        this.db = db;
        this.rules = rules;
        this.positions = positions;
        this.notices = notices;
    }

    @Transactional
    public void check(UUID user, LocalDate today, Function<String, AiSignalSummary> signals,
                      Function<NavRequest, AiFundNavHistory> history, Function<String, JsonNode> reviews) {
        var subscriptions = db.sql("""
                SELECT r.rule_id, r.fund_code, r.rule_type, r.threshold, r.updated_at
                FROM alert_rule r JOIN watchlist_item w ON w.user_id=r.user_id AND w.fund_code=r.fund_code
                WHERE r.user_id=:user AND r.enabled AND r.rule_type IN ('SIGNAL_CHANGE','RISK_LEVEL')
                ORDER BY r.rule_id FOR SHARE OF r, w
                """).param("user", user).query((r, n) -> new Subscription(r.getObject(1, UUID.class),
                r.getString(2), r.getString(3), r.getBigDecimal(4), r.getTimestamp(5).toInstant())).list();
        for (var rule : subscriptions) {
            if ("SIGNAL_CHANGE".equals(rule.kind())) {
                var direction = publishedDirection(rule.code(), today, reviews.apply(rule.code()));
                if (direction == null) {
                    var signal = publishedScore(rule.code(), today, signals.apply(rule.code()));
                    if (signal != null && Set.of("UP", "DOWN", "NEUTRAL").contains(Objects.toString(signal.direction(), ""))) {
                        direction = new Direction("score", signal.asOfDate(), signal.scoredAt(), signal.direction());
                    }
                }
                if (direction == null) continue;
                var payload = Map.<String, Object>of("title", "走势判断变化",
                        "basisDate", direction.date().toString(),
                        "explanation", "已发布的走势判断变为" + directionLabel(direction.value()) + "。");
                observe(user, "follow:" + rule.id(), rule.code(), rule.kind(), null, direction.at(),
                        rule.updatedAt() + ":" + direction.source(), direction.value(), true, payload);
            } else {
                var score = publishedScore(rule.code(), today, signals.apply(rule.code()));
                if (score == null || score.scoredAt().isBefore(rule.updatedAt())) continue;
                var risk = RISK.get(Objects.toString(score.riskLevel(), ""));
                if (risk == null || rule.threshold() == null) continue;
                var payload = Map.<String, Object>of("title", "基金风险达到提醒条件",
                        "basisDate", score.asOfDate().toString(),
                        "explanation", "已发布的风险等级为" + riskLabel(score.riskLevel()) + "，达到你设置的条件。");
                threshold(user, "follow:" + rule.id(), rule.code(), rule.kind(), null,
                        score.scoredAt(), rule.threshold(), risk, false, payload);
            }
        }
        var held = positions.positions(user);
        for (var position : held) {
            String code = position.fundCode();
            if (position.shares() == null || position.shares().signum() <= 0 || position.issue() != null
                    || position.navDate() == null || position.navDate().isAfter(today)) continue;
            rules.lock(user, code);
            var rule = rules.activeRule(user, code);
            if (rule == null) continue;
            Instant observed = position.navDate().atStartOfDay(SHANGHAI).toInstant();
            var payload = Map.<String, Object>of("basisDate", position.navDate().toString());
            threshold(user, "holding:" + code + ":TAKE_PROFIT", code, "TAKE_PROFIT", "SIMULATED", observed,
                    rule.takeProfitPct(), position.holdingGainRate(), false, payload);
            if (rule.reduceDrawdownPct() == null) continue;
            boolean loss = "HOLDING_LOSS".equals(rule.ruleParams() == null ? null : rule.ruleParams().get("downsideBasis"));
            String kind = loss ? "HOLDING_LOSS" : "DRAWDOWN";
            var value = loss ? position.holdingGainRate() : drawdown(code, position.navDate(),
                    history.apply(new NavRequest(code, position.navDate())));
            threshold(user, "holding:" + code + ":" + kind, code, kind, "SIMULATED", observed,
                    rule.reduceDrawdownPct(), value, true, payload);
        }
    }

    private static AiSignalSummary publishedScore(String code, LocalDate today, AiSignalSummary score) {
        return score != null && code.equals(score.fundCode()) && "SCORED".equals(score.scoreStatus())
                && score.scoredAt() != null && score.asOfDate() != null && !score.asOfDate().isAfter(today) ? score : null;
    }

    /** 正式一日分析和旧评分各自比较。同一基金切换来源时先建立基准。 */
    static Direction publishedDirection(String code, LocalDate today, JsonNode review) {
        if (review == null || !code.equals(review.path("fundCode").asText())) return null;
        try {
            Instant adopted = OffsetDateTime.parse(review.path("adoptedAt").asText()).toInstant();
            for (var period : review.path("periods")) {
                String decision = period.path("decision").asText();
                if (!"T1".equals(period.path("horizonId").asText()) || !"DATED_ANALYSIS".equals(period.path("kind").asText())
                        || !Set.of("UP", "DOWN").contains(decision)) continue;
                LocalDate day = LocalDate.parse(period.path("startDate").asText());
                if (!day.isBefore(today)) return new Direction("daily", day, adopted, decision);
            }
        } catch (DateTimeException error) {
            // 无法核对来源日期时等待下一轮。
        }
        return null;
    }

    private static String riskLabel(String risk) {
        return switch (risk) {
            case "HIGH" -> "高";
            case "MEDIUM" -> "中";
            default -> "低";
        };
    }

    private static String directionLabel(String direction) {
        return switch (direction) {
            case "UP" -> "偏向上涨";
            case "DOWN" -> "偏向下跌";
            default -> "走势平稳";
        };
    }

    static BigDecimal drawdown(String code, LocalDate date, AiFundNavHistory history) {
        if (history == null || !code.equals(history.fundCode()) || history.items() == null) return null;
        var byDate = new TreeMap<LocalDate, AiFundNavPoint>();
        for (var point : history.items()) {
            if (point != null && point.navDate() != null && !point.navDate().isAfter(date)) byDate.put(point.navDate(), point);
        }
        if (byDate.size() < DRAWDOWN_POINTS || !date.equals(byDate.lastKey())) return null;
        var points = byDate.descendingMap().values().stream().limit(DRAWDOWN_POINTS).toList();
        boolean accumulated = points.stream().allMatch(p -> positive(p.accumulatedNav()));
        var values = points.stream().map(p -> accumulated ? p.accumulatedNav() : p.unitNav()).toList();
        if (!values.stream().allMatch(RuleReminderChecker::positive)) return null;
        var peak = values.stream().max(BigDecimal::compareTo).orElseThrow();
        return values.get(0).divide(peak, MathContext.DECIMAL128).subtract(BigDecimal.ONE);
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private void threshold(UUID user, String key, String code, String kind, String scope, Instant observed,
                           BigDecimal limit, BigDecimal value, boolean atOrBelow, Map<String, Object> basis) {
        if (limit == null || value == null) return;
        boolean reached = atOrBelow ? value.compareTo(limit) <= 0 : value.compareTo(limit) >= 0;
        var payload = new HashMap<>(basis);
        if (scope != null) {
            String label = switch (kind) {
                case "TAKE_PROFIT" -> "持仓收益达到止盈条件";
                case "HOLDING_LOSS" -> "持仓亏损达到提醒条件";
                default -> "净值回撤达到提醒条件";
            };
            payload.put("title", label);
            payload.put("condition", (atOrBelow ? "达到或低于 " : "达到或高于 ") + percent(limit));
            payload.put("explanation", "当前为 " + percent(value) + ("DRAWDOWN".equals(kind) ? "，按最近 63 个净值日的高点计算。" : "。"));
        }
        observe(user, key, code, kind, scope, observed, limit.stripTrailingZeros().toPlainString(),
                reached ? "REACHED" : "CLEAR", false, payload);
    }

    private static String percent(BigDecimal value) {
        return value.movePointRight(2).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + "%";
    }

    /** 水位和消息在同一事务内保存。持续满足条件只推进水位；恢复后再次达到才发新消息。 */
    private void observe(UUID user, String key, String code, String kind, String scope, Instant observed,
                         String setting, String value, boolean direction, Map<String, Object> payload) {
        db.sql("SELECT pg_advisory_xact_lock(721134,hashtext(:key))")
                .param("key", user + ":" + key).query((r, n) -> 0).list();
        var previous = db.sql("""
                SELECT observed_at, setting, observed_value FROM reminder_delivery_state
                WHERE user_id=:user AND rule_key=:key
                """).param("user", user).param("key", key)
                .query((r, n) -> new Watermark(r.getTimestamp(1).toInstant(), r.getString(2), r.getString(3))).optional().orElse(null);
        if (previous != null && observed.isBefore(previous.at())) return;
        boolean sameSetting = previous != null && setting.equals(previous.setting());
        if (sameSetting && value.equals(previous.value()) && observed.equals(previous.at())) return;
        boolean triggered = direction
                ? sameSetting && !value.equals(previous.value()) && observed.isAfter(previous.at())
                : "REACHED".equals(value) && (!sameSetting || !"REACHED".equals(previous.value()));
        if (triggered) notices.observe(user, new Observation("trigger:" + UUID.randomUUID(), scope,
                code, kind, State.ACTIVE, payload, null));
        db.sql("""
                INSERT INTO reminder_delivery_state(user_id,rule_key,observed_at,setting,observed_value)
                VALUES(:user,:key,:at,:setting,:value)
                ON CONFLICT(user_id,rule_key) DO UPDATE SET observed_at=EXCLUDED.observed_at,
                    setting=EXCLUDED.setting, observed_value=EXCLUDED.observed_value
                """).param("user", user).param("key", key).param("at", OffsetDateTime.ofInstant(observed, ZoneOffset.UTC))
                .param("setting", setting).param("value", value).update();
    }

    public record NavRequest(String code, LocalDate date) {}
    record Direction(String source, LocalDate date, Instant at, String value) {}
    private record Subscription(UUID id, String code, String kind, BigDecimal threshold, Instant updatedAt) {}
    private record Watermark(Instant at, String setting, String value) {}
}
