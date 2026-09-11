package com.fundradar.core.advice;

import com.fundradar.core.integration.ai.AiPredictionClient;
import com.fundradar.core.simulation.*;
import com.fundradar.core.watchlist.api.DirectionExperimentResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.time.*;
import java.util.*;

/** 关闭网页仍留档；独立调度线程不阻塞模拟结算，跨实例数据库锁防止重复批次。 */
@Component
@ConditionalOnProperty(name="portfolio.advice.enabled",havingValue="true",matchIfMissing=true)
public class AdviceScheduler {
    private static final Logger LOGGER=LoggerFactory.getLogger(AdviceScheduler.class);
    private final DataSource dataSource;
    private final SimulationRepository positions;
    private final SimulationMarketClient market;
    private final AiPredictionClient predictions;
    private final AdviceService service;
    private final AdviceRepository repo;
    private final AdviceOutcomeClient outcomes;
    private final Clock clock;
    public AdviceScheduler(DataSource dataSource,SimulationRepository positions,SimulationMarketClient market,
                           AiPredictionClient predictions,AdviceService service,AdviceRepository repo,AdviceOutcomeClient outcomes,Clock clock) {
        this.dataSource=dataSource; this.positions=positions; this.market=market; this.predictions=predictions;
        this.service=service; this.repo=repo; this.outcomes=outcomes; this.clock=clock;
    }
    @Scheduled(scheduler="adviceTaskScheduler",fixedDelayString="${portfolio.advice.fixed-delay:PT30M}",initialDelayString="${portfolio.advice.initial-delay:PT20S}")
    public void tick() {
        var now=clock.instant().atZone(SimulationCalendar.ZONE);
        if(now.toLocalTime().isBefore(LocalTime.of(8,30))) return;
        try(var connection=dataSource.getConnection()) {
            try(var statement=connection.prepareStatement("SELECT pg_try_advisory_lock(721106,1)")) {
                var result=statement.executeQuery(); result.next(); if(!result.getBoolean(1)) return;
            }
            try { run(); }
            finally { try(var statement=connection.prepareStatement("SELECT pg_advisory_unlock(721106,1)")) { statement.execute(); } }
        } catch(Exception error) {
            LOGGER.error("AdviceScheduler.tick   >>>   daily advice failed",error);
            positions.job("portfolio-advice","FAILED","建议后台检查失败，原始报告与核验结果保留。",clock.instant(),true);
        }
    }
    public void run() {
        positions.job("portfolio-advice","RUNNING","正在保存今日建议并检查到期记录。",clock.instant(),false);
        SimulationCalendar calendar=null;
        try { calendar=new SimulationCalendar(market.calendar()); } catch(SimulationException ignored) { /* 各日报仍保存不可用原因。 */ }
        Map<String,DirectionExperimentResponse> cache=new HashMap<>();
        UUID after=null; int saved=0,failed=0;
        while(true) {
            var users=positions.workerUsers(after,50); if(users.isEmpty()) break;
            for(UUID user:users) {
                for(var position:positions.positions(user)) {
                    // 清仓后停止生成新建议，但旧记录的到期核验不依赖当前持仓。
                    if(position.shares().signum()==0 && position.totalSell().signum()>0) continue;
                    try {
                        if(cache.size()>256) cache.clear();
                        var prediction=position.shares().signum()>0 ? cache.computeIfAbsent(position.fundCode(),predictions::readExperiment) : null;
                        service.archive(user,position,prediction,calendar,clock.instant()); saved++;
                    } catch(Exception error) { failed++; LOGGER.error("AdviceScheduler.run   >>>   fundCode={}, archive failed",position.fundCode(),error); }
                }
            }
            after=users.get(users.size()-1);
        }
        after=null;
        var today=clock.instant().atZone(SimulationCalendar.ZONE).toLocalDate();
        while(true) {
            var batch=repo.pending(today,after,50); if(batch.isEmpty()) break;
            for(var row:batch) {
                try { repo.review(row,outcomes.read(row)); }
                catch(Exception error) { failed++; LOGGER.error("AdviceScheduler.run   >>>   reportId={}, review failed",row.reportId(),error); }
            }
            after=batch.get(batch.size()-1).reportId();
        }
        positions.job("portfolio-advice",failed==0 ? "SUCCEEDED" : "PARTIAL",failed==0 ? "每日建议与到期记录检查完成。" : "部分记录待重试，已保存报告保留。",clock.instant(),true);
        LOGGER.info("AdviceScheduler.run   >>>   checked={}, failures={}",saved,failed);
    }
}
