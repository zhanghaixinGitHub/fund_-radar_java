package com.fundradar.core.notification.service;

import com.fundradar.core.integration.ai.AiFundClient;
import com.fundradar.core.fund.api.FundNewsFactsResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.time.*;
import java.util.*;

/** 新增本地站内复查任务；默认关闭，和原训练/交易/分析定时任务独立。检查只读公共缓存，不触发采集。 */
@Component
@ConditionalOnProperty(name="notification.review.enabled",havingValue="true")
public class ReviewNoticeScheduler {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(ReviewNoticeScheduler.class);
    private final ReviewNoticeChecker checker;private final AiFundClient funds;private final DataSource ds;private final Clock clock;
    public ReviewNoticeScheduler(ReviewNoticeChecker checker,AiFundClient funds,DataSource ds,Clock clock) { this.checker=checker;this.funds=funds;this.ds=ds;this.clock=clock; }
    @Scheduled(fixedDelayString="${notification.review.fixed-delay:PT15M}",initialDelayString="${notification.review.initial-delay:PT30S}")
    public void tick() {
        try(var connection=ds.getConnection()) {
            try(var lock=connection.prepareStatement("SELECT pg_try_advisory_lock(721133,1)")) {
                try(var row=lock.executeQuery()) { row.next();if(!row.getBoolean(1)) return; }
            }
            try {
                UUID after=new UUID(0,0);int users=0,changes=0,failures=0;
                var batch=checker.usersAfter(after);
                if(batch.isEmpty()) { LOG.info("ReviewNoticeScheduler.tick   >>> 无已设置条件或订阅的账户，本次未创建提醒");return; }
                FundNewsFactsResponse news=null;
                try { news=funds.getFundNewsFacts("002112"); }
                catch(RuntimeException error) { LOG.warn("ReviewNoticeScheduler.tick   >>> 公共消息暂不可用；本人日期检查继续",error); }
                LocalDate today=clock.instant().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
                while(!batch.isEmpty()) {
                    for(UUID user:batch) {
                        try { changes+=checker.check(user,news,today); }
                        catch(RuntimeException error) { failures++;LOG.error("ReviewNoticeScheduler.tick   >>> 单个账户复查失败，保留已有事项",error); }
                        users++;after=user;
                    }
                    batch=checker.usersAfter(after);
                }
                LOG.info("ReviewNoticeScheduler.tick   >>> 站内检查结束, accounts={}, changes={}, failures={}",users,changes,failures);
            } finally { try(var unlock=connection.prepareStatement("SELECT pg_advisory_unlock(721133,1)")) { unlock.execute(); } }
        } catch(Exception error) { LOG.error("ReviewNoticeScheduler.tick   >>> 站内检查失败",error); }
    }
}
