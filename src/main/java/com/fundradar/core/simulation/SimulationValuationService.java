package com.fundradar.core.simulation;

import com.fundradar.core.integration.ai.MarketRevisionClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import static com.fundradar.core.simulation.SimulationTypes.*;

/** 独立收益更新器：没有交易服务或定投调度依赖，只读取已确认账本并保存收益投影。 */
@Service
public class SimulationValuationService {
    private final SimulationRepository repo;
    private final SimulationMarketClient market;
    private final MarketRevisionClient revisions;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Map<String,Cached> cache=new HashMap<>();
    private CalendarData calendar;
    private String calendarRevision;
    private Object completed;
    private record Cached(MarketRevisionClient.Query query,String revision,Market market) {}
    public record Result(int updated,int waiting,boolean unchanged) {}
    public SimulationValuationService(SimulationRepository repo,SimulationMarketClient market,MarketRevisionClient revisions,
                                      TransactionTemplate tx,Clock clock) {
        this.repo=repo; this.market=market; this.revisions=revisions; this.tx=tx; this.clock=clock;
    }

    synchronized Result refresh(UUID owner) {
        Instant now=clock.instant(); LocalDate today=now.atZone(SimulationCalendar.ZONE).toLocalDate();
        var needs=repo.valuationNeeds(owner);
        if(needs.isEmpty()) return new Result(0,0,true);
        var queries=needs.entrySet().stream().map(e -> new MarketRevisionClient.Query(e.getKey(),e.getKey(),
                e.getValue().minusDays(7),today,"SIMULATION")).toList();
        var versions=new HashMap<String,MarketRevisionClient.Revision>(); String observedCalendar=null;
        for(int offset=0;offset<queries.size();offset+=50) {
            var snapshot=revisions.read(queries.subList(offset,Math.min(offset+50,queries.size())));
            if(observedCalendar!=null&&!observedCalendar.equals(snapshot.calendarRevision()))
                throw new IllegalStateException("VALUATION_CALENDAR_CHANGED");
            observedCalendar=snapshot.calendarRevision(); versions.putAll(snapshot.items());
        }
        Object work=List.of(owner,today,repo.valuationRevision(owner),queries,observedCalendar,Map.copyOf(versions));
        if(work.equals(completed)) return new Result(0,0,true);
        if(calendar==null||!Objects.equals(calendarRevision,observedCalendar)) {
            calendar=market.calendar(); calendarRevision=observedCalendar;
        }
        var tradingCalendar=new SimulationCalendar(calendar);
        cache.keySet().retainAll(needs.keySet());
        int updated=0,waiting=0;
        for(var query:queries) {
            Market info;
            try {
                String revision=versions.get(query.key()).value(); Cached previous=cache.get(query.key());
                if(previous==null||!previous.query().equals(query)||!previous.revision().equals(revision)) {
                    previous=new Cached(query,revision,market.market(query.fundCode(),query.startDate(),query.endDate()));
                    cache.put(query.key(),previous);
                }
                info=previous.market();
            } catch(SimulationException error) {
                // 行情服务不可用时保留既有收益，不把网络失败写成历史净值缺失。
                cache.remove(query.key()); waiting++;
                tx.executeWithoutResult(status -> {
                    repo.lock(owner);
                    repo.valuationState(owner,query.fundCode(),new ValuationState(now,null,"RETRY","行情暂时无法读取，收益稍后更新。"));
                });
                continue;
            }
            List<String> navDates=info.navs().stream().filter(n -> !n.navDate().isAfter(today)
                    &&(n.announcedOn()==null||!n.announcedOn().isAfter(today))).map(n -> n.navDate().toString()).distinct().sorted().toList();
            Boolean success=tx.execute(status -> {
                repo.lock(owner);
                Position current=repo.positions(owner).stream().filter(p -> p.fundCode().equals(query.fundCode())).findFirst().orElse(null);
                if(current==null) return true; // 期间撤销了唯一委托时，不重新创建持仓。
                var opening=repo.openingBalances(owner).get(query.fundCode());
                var orders=repo.orders(owner,query.fundCode());
                if(opening==null&&orders.stream().noneMatch(o -> "CONFIRMED".equals(o.status()))) return true;
                try {
                    var value=SimulationAccounting.valueConfirmed(query.fundCode(),current.fundName(),orders,info,tradingCalendar,today,opening);
                    repo.saveValuation(owner,current,value,new ValuationState(now,navDates,"CURRENT",null),now);
                    return true;
                } catch(SimulationException error) {
                    repo.valuationState(owner,query.fundCode(),new ValuationState(now,navDates,"REVIEW",error.getMessage()));
                    return false;
                }
            });
            if(Boolean.TRUE.equals(success)) updated++; else waiting++;
        }
        if(waiting==0) completed=work;
        return new Result(updated,waiting,false);
    }
}
