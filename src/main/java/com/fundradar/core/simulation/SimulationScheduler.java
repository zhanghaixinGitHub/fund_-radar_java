package com.fundradar.core.simulation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import static com.fundradar.core.simulation.SimulationTypes.*;

/** 后台执行定投、结算和估值；数据库锁确保多实例只跑一份，网页关闭不影响任务。 */
@Component
@ConditionalOnProperty(name="simulation.enabled",havingValue="true",matchIfMissing=true)
public class SimulationScheduler {
    private static final Logger LOGGER=LoggerFactory.getLogger(SimulationScheduler.class);
    private final SimulationRepository repo;
    private final SimulationMarketClient market;
    private final SimulationService service;
    private final DataSource dataSource;
    private final Clock clock;
    public SimulationScheduler(SimulationRepository repo,SimulationMarketClient market,SimulationService service,DataSource dataSource,Clock clock) {
        this.repo=repo; this.market=market; this.service=service; this.dataSource=dataSource; this.clock=clock;
    }
    @Scheduled(fixedDelayString="${simulation.fixed-delay:PT1M}",initialDelayString="${simulation.initial-delay:PT15S}")
    public void tick() {
        try(Connection lock=dataSource.getConnection()) {
            try(var statement=lock.prepareStatement("SELECT pg_try_advisory_lock(721105,1)")) {
                var result=statement.executeQuery(); result.next(); if(!result.getBoolean(1)) return;
            }
            try { run(); }
            finally { try(var release=lock.prepareStatement("SELECT pg_advisory_unlock(721105,1)")) { release.execute(); } }
        } catch(Exception error) {
            LOGGER.error("SimulationScheduler.tick   >>> simulation processing failed",error);
            repo.job("settlement","FAILED","后台处理失败，已有订单与上次估值保留。",clock.instant(),true);
        }
    }
    private void run() {
        Instant now=clock.instant(); repo.job("settlement","RUNNING","正在检查定投与待确认订单。",now,false);
        var needs=repo.marketNeeds();
        if(needs.isEmpty()) { repo.job("settlement","SUCCEEDED","后台正常，等待模拟交易或定投计划。",clock.instant(),true); return; }
        var calendar=new SimulationCalendar(market.calendar()); LocalDate today=calendar.today(now);
        Map<String,Market> data=new HashMap<>(); int failed=0;
        for(var need : needs.entrySet()) {
            String code=need.getKey();
            try {
                LocalDate start=need.getValue().isAfter(today) ? today.minusDays(35) : need.getValue().minusDays(7);
                Market info=market.market(code,start,today); data.put(code,info);
                JobState refresh=repo.job("refresh:"+code);
                boolean interval=refresh==null || Duration.between(refresh.attemptedAt(),now).toMinutes()>=30;
                int hour=now.atZone(SimulationCalendar.ZONE).getHour();
                boolean window=info.dividendsVerifiedAt()==null || hour==7 || hour==20 || hour==22 || info.refreshStatus().equals("FAILED");
                if(interval && window) {
                    repo.job("refresh:"+code,"REQUESTED","已请求核验公共净值与分红。",now,false);
                    market.refresh(List.of(code));
                }
            } catch(Exception error) { failed++; LOGGER.warn("SimulationScheduler.run   >>> market unavailable, fundCode={}",code,error); }
        }
        UUID after=null; int users=0;
        while(true) {
            var batch=repo.workerUsers(after,50); if(batch.isEmpty()) break;
            for(UUID user : batch) {
                try { service.processUser(user,calendar,data,now); users++; }
                catch(Exception error) { failed++; LOGGER.error("SimulationScheduler.run   >>> account processing failed, accountId={}",user,error); }
            }
            after=batch.get(batch.size()-1);
        }
        repo.job("settlement",failed==0 ? "SUCCEEDED" : "PARTIAL",failed==0 ? "后台检查完成。" : "部分行情或账目待处理，已保留原记录。",clock.instant(),true);
        LOGGER.debug("SimulationScheduler.run   >>> accounts={}, failures={}",users,failed);
    }
}
