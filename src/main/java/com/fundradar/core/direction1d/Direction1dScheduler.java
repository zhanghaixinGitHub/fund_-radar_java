package com.fundradar.core.direction1d;

import com.fasterxml.jackson.databind.JsonNode;
import com.fundradar.core.integration.ai.MarketRevisionClient;
import com.fundradar.core.integration.ai.MarketRevisionClient.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/** 只在到期数据变化后核对原预测；新预测由手动入口和一键同步生成。 */
@Component
public class Direction1dScheduler {
    private static final Logger LOG=LoggerFactory.getLogger(Direction1dScheduler.class);
    private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private final DataSource dataSource;
    private final Direction1dRepository repo;
    private final Direction1dClient client;
    private final MarketRevisionClient revisions;
    private final Clock clock;
    private final boolean review;
    private final boolean training;
    private LocalDate trainingWeek;

    public Direction1dScheduler(DataSource dataSource,Direction1dRepository repo,Direction1dClient client,
            MarketRevisionClient revisions,Clock clock,
            @Value("${direction1d.review-enabled:true}") boolean review,
            @Value("${direction1d.training-enabled:true}") boolean training) {
        this.dataSource=dataSource; this.repo=repo; this.client=client; this.revisions=revisions;
        this.clock=clock; this.review=review; this.training=training;
    }
    @Scheduled(scheduler="direction1dTaskScheduler",fixedDelayString="${direction1d.fixed-delay:PT5M}",initialDelayString="${direction1d.initial-delay:PT10S}")
    public void tick() {
        try(var c=dataSource.getConnection();var lock=c.prepareStatement("SELECT pg_try_advisory_lock(721111,1)")) {
            var rs=lock.executeQuery();rs.next();if(!rs.getBoolean(1))return;
            try { run(); } finally { try(var release=c.prepareStatement("SELECT pg_advisory_unlock(721111,1)")){release.execute();} }
        } catch(Exception error) {
            LOG.error("Direction1dScheduler.tick   >>>   后台核对失败，旧原文保留",error);
            repo.health("FAILED",0,1,"结果核对暂未完成，历史记录保留。",true);
        }
    }
    public void run() {
        int checked=0,failed=0; UUID after=null;
        if(review) while(true) {
            var batch=repo.reviewPage(after); if(batch.isEmpty())break;
            var queries=batch.stream().map(r->new Query(r.get("forecast_id").toString(),r.get("fund_code").toString(),
                    LocalDate.parse(r.get("base_nav_date").toString()),LocalDate.parse(r.get("target_nav_date").toString()),"LABEL")).toList();
            var snapshot=revisions.read(queries);
            for(var r:batch) {
                UUID id=(UUID)r.get("forecast_id"),job=(UUID)r.get("source_job_id");
                var version=snapshot.items().get(id.toString());
                if(!version.ready() || version.value().equals(r.get("market_revision"))) continue;
                try {
                    // 缺数据时只等待下一次手动同步，版本检查与取标签之间的数据变化也不能触发补拉。
                    JsonNode label=client.get("/labels/"+job+"?fetch_missing=false");
                    if("AVAILABLE".equals(label.path("payload").path("status").asText())) {
                        repo.outcome(id,label); // 返回时结果事务已提交，之后才允许 Python 纳入学习。
                        if(label.path("payload").path("training_eligible").asBoolean(false)) {
                            var ack=client.post("/labels/"+job+"/assessed",Map.of("label_hash",label.path("content_hash").asText()));
                            if(ack==null || !"ACKNOWLEDGED".equals(ack.path("status").asText()))
                                throw new IllegalStateException("LABEL_ACK_NOT_CONFIRMED");
                        }
                        // 首次答案使用预测时冻结的基准净值；再核对一次允许追加期间发生的基准修订。
                        // 随后的相同版本跨重启也跳过，回执或水位写入失败则继续重试。
                        if(Boolean.TRUE.equals(r.get("has_outcome"))) repo.reviewed(id,version.value());
                        checked++;
                    }
                } catch(Exception error) {failed++;LOG.error("Direction1dScheduler.run   >>>   forecastId={}, 到期核对失败",id,error);}
            }
            after=(UUID)batch.get(batch.size()-1).get("forecast_id");
        }
        if(training && failed==0) checkWeeklyTraining();
        repo.health(failed==0?"SUCCEEDED":"PARTIAL",checked,failed,"到期结果核对完成；未公布净值继续等待。",true);
        if(checked>0 || failed>0) {
            LOG.info("Direction1dScheduler.run   >>>   checked={}, failed={}",checked,failed);
        }
    }

    /** 每周只提交一次固定训练检查；周日12点前不请求，实际窗口与无新增样本跳过仍由 Python 校验。 */
    private void checkWeeklyTraining() {
        var now=clock.instant().atZone(ZONE);
        LocalDate sunday=now.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY));
        if(sunday.equals(trainingWeek) || now.isBefore(sunday.atTime(12,0).atZone(ZONE))) return;
        var result=client.post("/training-jobs",Map.of());
        if(result==null || !Set.of("NOT_DUE","QUEUED","RUNNING","SUCCEEDED").contains(result.path("state").asText()))
            throw new IllegalStateException("WEEKLY_TRAINING_CHECK_FAILED");
        trainingWeek=sunday;
    }
}
