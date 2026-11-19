package com.fundradar.core.notification.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fundradar.core.integration.ai.*;
import com.fundradar.core.prediction.MultiPredictionClient;
import com.fundradar.core.fund.api.FundNewsFactsResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.time.*;
import java.util.*;
import java.util.function.Function;

/** 新增本地站内复查任务；默认关闭，和原训练/交易/分析定时任务独立。检查只读公共缓存，不触发采集。 */
@Component
@ConditionalOnExpression("${notification.rules.enabled:false} or ${notification.review.enabled:false}")
public class ReviewNoticeScheduler {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(ReviewNoticeScheduler.class);
    private final ReviewNoticeChecker checker;
    private final RuleReminderChecker ruleChecker;
    private final AiFundClient funds;
    private final AiSignalClient signals;
    private final MultiPredictionClient predictions;
    private final DataSource ds;
    private final Clock clock;
    private final boolean accountDates;
    private final boolean runOnceOnStart;

    public ReviewNoticeScheduler(ReviewNoticeChecker checker, RuleReminderChecker ruleChecker, AiFundClient funds,
                                 AiSignalClient signals, MultiPredictionClient predictions, DataSource ds, Clock clock,
                                 @Value("${notification.review.enabled:false}") boolean accountDates,
                                 @Value("${notification.rules.run-once-on-start:false}") boolean runOnceOnStart) {
        this.checker = checker;
        this.ruleChecker = ruleChecker;
        this.funds = funds;
        this.signals = signals;
        this.predictions = predictions;
        this.ds = ds;
        this.clock = clock;
        this.accountDates = accountDates;
        this.runOnceOnStart = runOnceOnStart;
    }
    @EventListener(ApplicationReadyEvent.class)
    public void runRequestedCheck() {
        if (runOnceOnStart) tick();
    }

    @Scheduled(cron = "0 0 6 * * *", zone = "Asia/Shanghai")
    public void tick() {
        try(var connection=ds.getConnection()) {
            try(var lock=connection.prepareStatement("SELECT pg_try_advisory_lock(721133,1)")) {
                try(var row=lock.executeQuery()) { row.next();if(!row.getBoolean(1)) return; }
            }
            try {
                UUID after=new UUID(0,0);int users=0,changes=0,failures=0;
                var batch=checker.usersAfter(after);
                if(batch.isEmpty()) { LOG.info("ReviewNoticeScheduler.tick   >>> 无已设置条件或订阅的账户，本次未创建提醒");return; }
                Function<String, FundNewsFactsResponse> news = perRound(funds::getFundNewsFacts);
                Function<String, JsonNode> reviews = perRound(code -> predictions.get("/funds/" + code + "/review-v2"));
                Function<String, AiSignalSummary> scores = perRound(signals::latestPublishedSignal);
                Function<RuleReminderChecker.NavRequest, AiFundNavHistory> history = perRound(
                        req -> funds.getFundNavHistory(req.code(), req.date().minusYears(1), req.date()));
                LocalDate today=clock.instant().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
                while(!batch.isEmpty()) {
                    for(UUID user:batch) {
                        try { changes+=checker.checkWithNews(user,news,today,accountDates); }
                        catch(RuntimeException error) { failures++;LOG.error("ReviewNoticeScheduler.tick   >>> 单个账户复查失败，保留已有事项",error); }
                        try { ruleChecker.check(user,today,scores,history,reviews); }
                        catch(RuntimeException error) { failures++;LOG.error("站内规则检查失败，保留上一轮检查结果",error); }
                        users++;after=user;
                    }
                    batch=checker.usersAfter(after);
                }
                LOG.info("ReviewNoticeScheduler.tick   >>> 站内检查结束, accounts={}, changes={}, failures={}",users,changes,failures);
            } finally { try(var unlock=connection.prepareStatement("SELECT pg_advisory_unlock(721133,1)")) { unlock.execute(); } }
        } catch(Exception error) { LOG.error("ReviewNoticeScheduler.tick   >>> 站内检查失败",error); }
    }
    /** 同一轮只读一次相同的公开资料。暂缺或失败不会变成条件解除。 */
    private static <K, V> Function<K, V> perRound(Function<K, V> source) {
        var cache = new HashMap<K, Optional<V>>();
        return key -> cache.computeIfAbsent(key, k -> {
            try {
                return Optional.ofNullable(source.apply(k));
            } catch (RuntimeException error) {
                LOG.warn("提醒资料暂不可读，本轮等待。资料键={}，异常类型={}", k, error.getClass().getSimpleName());
                return Optional.empty();
            }
        }).orElse(null);
    }
}
