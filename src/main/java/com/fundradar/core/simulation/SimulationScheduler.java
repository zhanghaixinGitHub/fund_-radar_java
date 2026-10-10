package com.fundradar.core.simulation;

import com.fundradar.core.simulation.SimulationService.PlanRunStats;
import com.fundradar.core.integration.ai.MarketRevisionClient;
import com.fundradar.core.integration.ai.MarketRevisionClient.Query;
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
    private final MarketRevisionClient revisions;
    // 仅缓存已成功读取的公共行情；重启后首次重新核验，不以缓存替代数据库中的订单幂等与账户锁。
    private final Map<String,CachedMarket> marketCache=new HashMap<>();
    private record CachedMarket(Query query,String revision,Market data) {}
    private String calendarRevision;
    private SimulationCalendar cachedCalendar;
    private Object completedWork;
    public SimulationScheduler(SimulationRepository repo,SimulationMarketClient market,SimulationService service,DataSource dataSource,Clock clock,
                               MarketRevisionClient revisions) {
        this.repo=repo; this.market=market; this.service=service; this.dataSource=dataSource; this.clock=clock; this.revisions=revisions;
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
    /** 管理员手动补录：为每个进行中计划按前一交易日净值立即补入一期；与定时任务共用同一把数据库锁，冲突时直接拒绝。 */
    public RecurringRunResult runDueManually() {
        try(Connection lock=dataSource.getConnection()) {
            try(var statement=lock.prepareStatement("SELECT pg_try_advisory_lock(721105,1)")) {
                var result=statement.executeQuery(); result.next();
                if(!result.getBoolean(1)) throw new SimulationException("SIM_RUN_BUSY","后台正在执行定投或结算，请稍后重试。");
            }
            try {
                // 手动执行始终读取最新资料，并使下一轮后台重新检查，避免复用手动处理前的账目水位。
                completedWork=null; marketCache.clear();
                PlanRunStats stats=runCatchUp();
                String message=stats.ordersCreated()>0
                        ? "检查 %d 个进行中计划，按前一交易日净值补入 %d 笔定投并确认，跳过 %d 个。".formatted(stats.plansChecked(),stats.ordersCreated(),stats.plansSkipped())
                        : "检查 %d 个进行中计划，没有可补入的定投（今日已补入或行情暂不可用），跳过 %d 个。".formatted(stats.plansChecked(),stats.plansSkipped());
                return new RecurringRunResult(clock.instant(),stats.plansChecked(),stats.ordersCreated(),stats.plansSkipped(),message);
            } finally { try(var release=lock.prepareStatement("SELECT pg_advisory_unlock(721105,1)")) { release.execute(); } }
        } catch(SimulationException error) { throw error;
        } catch(Exception error) {
            LOGGER.error("SimulationScheduler.runDueManually   >>> manual recurring run failed",error);
            repo.job("settlement","FAILED","后台处理失败，已有订单与上次估值保留。",clock.instant(),true);
            throw new SimulationException("SIM_RUN_FAILED","手动执行失败，已有订单与持仓保留，请稍后重试。");
        }
    }
    void run() {
        Instant now=clock.instant(); LocalDate today=now.atZone(SimulationCalendar.ZONE).toLocalDate();
        String localRevision=repo.workRevision();
        var needs=repo.marketNeeds();
        marketCache.keySet().retainAll(needs.keySet());
        if(needs.isEmpty()) {
            completedWork=null;
            repo.job("settlement","SUCCEEDED","后台正常，等待模拟交易或定投计划。",now,true);
            return;
        }
        List<Query> queries=needs.entrySet().stream().map(n->new Query(n.getKey(),n.getKey(),
                n.getValue().isAfter(today)?today.minusDays(35):n.getValue().minusDays(7),today,"SIMULATION")).toList();
        Map<String,MarketRevisionClient.Revision> versions=new HashMap<>(); String currentCalendar=null;
        for(int i=0;i<queries.size();i+=50) {
            var snapshot=revisions.read(queries.subList(i,Math.min(i+50,queries.size())));
            if(currentCalendar!=null && !currentCalendar.equals(snapshot.calendarRevision()))
                throw new IllegalStateException("CALENDAR_CHANGED_DURING_CHECK");
            currentCalendar=snapshot.calendarRevision(); versions.putAll(snapshot.items());
        }
        // 日切、10点执行窗口、15点截止时需要检查，即使净值内容没有变化。
        // 不按分钟改变工作版本；无新委托/行情时不会反复重算全部账户。
        var time=now.atZone(SimulationCalendar.ZONE).toLocalTime();
        int phase=time.isBefore(LocalTime.of(10,0))?0:time.isBefore(LocalTime.of(15,0))?1:2;
        Object work=List.of(localRevision,today,phase,currentCalendar,queries,Map.copyOf(versions));
        if(work.equals(completedWork)) return;
        if(cachedCalendar==null || !currentCalendar.equals(calendarRevision)) {
            cachedCalendar=new SimulationCalendar(market.calendar()); calendarRevision=currentCalendar;
        }
        repo.job("settlement","RUNNING","正在检查定投与待确认订单。",now,false);
        Map<String,Market> data=new HashMap<>(); int failed=0;
        for(Query query:queries) {
            try {
                String version=versions.get(query.key()).value(); CachedMarket cached=marketCache.get(query.key());
                if(cached==null || !cached.query().equals(query) || !cached.revision().equals(version)) {
                    cached=new CachedMarket(query,version,market.market(query.fundCode(),query.startDate(),query.endDate()));
                    marketCache.put(query.key(),cached);
                }
                data.put(query.fundCode(),cached.data());
            } catch(Exception error) { failed++; LOGGER.warn("SimulationScheduler.run   >>> 行情读取失败，fundCode={}",query.fundCode(),error); }
        }
        UUID after=null; int users=0;
        while(true) {
            var batch=repo.workerUsers(after,50); if(batch.isEmpty()) break;
            for(UUID user : batch) {
                try { service.processUser(user,cachedCalendar,data,now); users++; }
                catch(Exception error) { failed++; LOGGER.error("SimulationScheduler.run   >>> account processing failed, accountId={}",user,error); }
            }
            after=batch.get(batch.size()-1);
        }
        repo.job("settlement",failed==0 ? "SUCCEEDED" : "PARTIAL",failed==0 ? "后台检查完成。" : "部分行情或账目待处理，已保留原记录。",clock.instant(),true);
        // 使用执行前快照：执行期间发生的新委托/修订，下一轮仍会被发现。失败不推进，允许重试。
        if(failed==0) completedWork=work;
        LOGGER.debug("SimulationScheduler.run   >>> accounts={}, failures={}",users,failed);
    }
    /** 手动补录与定时检查共用行情加载和逐用户锁；补录一期后立即走同一套结算确认。 */
    private PlanRunStats runCatchUp() {
        Instant now=clock.instant(); repo.job("settlement","RUNNING","正在补录定投并结算。",now,false);
        var needs=repo.marketNeeds();
        if(needs.isEmpty()) { repo.job("settlement","SUCCEEDED","后台正常，等待模拟交易或定投计划。",clock.instant(),true); return PlanRunStats.zero(); }
        var calendar=new SimulationCalendar(market.calendar()); LocalDate today=calendar.today(now);
        var load=loadMarkets(needs,today); int failed=load.failed();
        PlanRunStats total=PlanRunStats.zero(); UUID after=null; int users=0;
        while(true) {
            var batch=repo.workerUsers(after,50); if(batch.isEmpty()) break;
            for(UUID user : batch) {
                try { total=total.plus(service.catchUpUser(user,calendar,load.data(),now)); users++; }
                catch(Exception error) { failed++; LOGGER.error("SimulationScheduler.runCatchUp   >>> account processing failed, accountId={}",user,error); }
            }
            after=batch.get(batch.size()-1);
        }
        repo.job("settlement",failed==0 ? "SUCCEEDED" : "PARTIAL",failed==0 ? "定投补录与结算完成。" : "部分行情或账目待处理，已保留原记录。",clock.instant(),true);
        LOGGER.debug("SimulationScheduler.runCatchUp   >>> accounts={}, failures={}",users,failed);
        return total;
    }
    private record MarketLoad(Map<String,Market> data,int failed) {}
    private MarketLoad loadMarkets(Map<String,LocalDate> needs,LocalDate today) {
        Map<String,Market> data=new HashMap<>(); int failed=0;
        for(var need : needs.entrySet()) {
            String code=need.getKey();
            try {
                LocalDate start=need.getValue().isAfter(today) ? today.minusDays(35) : need.getValue().minusDays(7);
                Market info=market.market(code,start,today); data.put(code,info);
                // 公共净值和分红由手动同步更新；结算读取不能再偷偷启动采集。
            } catch(Exception error) { failed++; LOGGER.warn("SimulationScheduler.loadMarkets   >>> market unavailable, fundCode={}",code,error); }
        }
        return new MarketLoad(data,failed);
    }
}
