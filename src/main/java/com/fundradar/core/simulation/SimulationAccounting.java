package com.fundradar.core.simulation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import static com.fundradar.core.simulation.SimulationTypes.*;

/** 可重放的十进制模拟账务：先进先出、现金分红、净值更正和资金流中性收益。 */
public final class SimulationAccounting {
    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(8);
    private SimulationAccounting() {}
    public static BigDecimal precise(BigDecimal value) { return value.setScale(8,RoundingMode.HALF_UP); }
    private static BigDecimal money(BigDecimal value) { return value.setScale(2,RoundingMode.HALF_UP); }
    private static class Lot {
        BigDecimal shares, cost;
        Lot(BigDecimal shares, BigDecimal cost) { this.shares=shares; this.cost=cost; }
    }

    public static Calculation calculate(String code, String name, List<Order> input, Market market,
                                        SimulationCalendar calendar, LocalDate today) {
        List<Order> orders = input.stream().filter(o -> !o.status().equals("CANCELLED"))
                .sorted(Comparator.comparing(Order::tradeDate).thenComparing(Order::createdAt).thenComparing(Order::orderId))
                .toList();
        if (orders.isEmpty()) return empty(code,name);
        var navs = new TreeMap<LocalDate,Nav>();
        for (var nav : market.navs()) {
            if (!nav.navDate().isAfter(today) && (nav.announcedOn()==null || !nav.announcedOn().isAfter(today))) {
                if (nav.unitNav()==null || nav.unitNav().signum()<=0 || navs.put(nav.navDate(),nav)!=null) {
                    throw invalid("净值日期重复或价格无效，等待数据核对。");
                }
            }
        }
        LocalDate first = orders.get(0).tradeDate();
        if (navs.isEmpty() || navs.lastKey().isBefore(first)) return pending(code,name,input);
        if (market.dividendsVerifiedAt()==null) throw invalid("分红资料尚未核验，暂不确认交易和收益。");
        if (market.dividendsVerifiedAt().atZone(SimulationCalendar.ZONE).toLocalDate().isBefore(navs.lastKey())) {
            throw invalid("分红核验日期早于最新净值，等待后台更新后结算。");
        }
        var applied = new ArrayList<Order>();
        // 早期订单未确认时不能越过它计算后面的仓位；确认日不足的同日订单统一等待。
        LocalDate blockedDate = null;
        for (var order : orders) {
            if (order.status().equals("CONFIRMED")) {
                if (!navs.containsKey(order.tradeDate())) throw invalid("已确认交易的净值缺失，保留上次估值等待核对。");
                applied.add(order);
            } else if (!order.eligibleDate().isAfter(today) && navs.containsKey(order.tradeDate())) {
                applied.add(order);
            } else {
                if (blockedDate==null || order.tradeDate().isBefore(blockedDate)) blockedDate=order.tradeDate();
            }
        }
        if (blockedDate!=null) {
            LocalDate limit=blockedDate;
            applied.removeIf(o -> !o.tradeDate().isBefore(limit));
            navs.tailMap(limit,true).clear();
        }
        if (applied.isEmpty() || navs.isEmpty() || navs.lastKey().isBefore(first)) return pending(code,name,input);
        var dividends = canonicalDividends(market.dividends(),first,navs.lastKey());
        var lots = new ArrayDeque<Lot>();
        var executions = new ArrayList<Execution>();
        var daily = new ArrayList<Daily>();
        var entitlements = new LinkedHashMap<String,DividendEntry>();
        var endShares = new TreeMap<LocalDate,BigDecimal>();
        var byDate = new HashMap<LocalDate,List<Order>>();
        applied.forEach(o -> byDate.computeIfAbsent(o.tradeDate(),d -> new ArrayList<>()).add(o));
        BigDecimal shares=ZERO,cost=ZERO,buy=ZERO,sell=ZERO,realized=ZERO,dividendGain=ZERO,previousGain=ZERO;
        LocalDate previousDate=null;
        Nav previousNav=navs.lowerEntry(first)==null ? null : navs.lowerEntry(first).getValue();
        for (var nav : navs.tailMap(first,true).values()) {
            LocalDate date=nav.navDate();
            BigDecimal dividendPerUnit=ZERO;
            for (var div : dividends) {
                if (!date.equals(div.exDate())) continue;
                var entitlementDate = div.recordDate().equals(div.exDate()) ? div.recordDate().minusDays(1) : div.recordDate();
                var historical = endShares.floorEntry(entitlementDate);
                BigDecimal entitled=historical==null ? ZERO : historical.getValue();
                BigDecimal amount=money(entitled.multiply(div.cashPerUnit()));
                dividendGain=dividendGain.add(amount);
                dividendPerUnit=dividendPerUnit.add(div.cashPerUnit());
                if (entitled.signum()>0) entitlements.put(div.eventKey(),new DividendEntry(div.eventKey(),div.exDate(),
                        div.payDate(),entitled,div.cashPerUnit(),amount,div.revision(),!div.payDate().isAfter(today)));
            }
            if (shares.signum()>0 && previousNav!=null && previousNav.accumulatedNav()!=null && nav.accumulatedNav()!=null) {
                BigDecimal cashChange=nav.accumulatedNav().subtract(nav.unitNav())
                        .subtract(previousNav.accumulatedNav().subtract(previousNav.unitNav()));
                if (cashChange.subtract(dividendPerUnit).abs().compareTo(new BigDecimal("0.0005"))>0) {
                    throw invalid("净值与分红或份额折算不能对齐，相关收益和交易等待核对。");
                }
            }
            for (var order : byDate.getOrDefault(date,List.of())) {
                BigDecimal quantity,gross,released=ZERO,gain=ZERO;
                if (order.side().equals("BUY")) {
                    quantity=order.amount().divide(nav.unitNav(),8,RoundingMode.DOWN);
                    if (quantity.signum()<=0) throw invalid("买入金额不足以形成有效模拟份额。");
                    gross=order.amount();
                    lots.add(new Lot(quantity,gross));
                    shares=shares.add(quantity); cost=cost.add(gross); buy=buy.add(gross);
                } else {
                    quantity=order.shares();
                    if (quantity.compareTo(shares)>0) throw invalid("历史净值修正后卖出份额不足，需核对已有交易。");
                    BigDecimal remaining=quantity;
                    while (remaining.signum()>0) {
                        Lot lot=lots.peek();
                        if (lot==null) throw invalid("份额批次不足，停止结算。");
                        BigDecimal used=remaining.min(lot.shares);
                        BigDecimal allocated=used.compareTo(lot.shares)==0 ? lot.cost :
                                lot.cost.multiply(used).divide(lot.shares,8,RoundingMode.HALF_UP);
                        released=released.add(allocated);
                        remaining=remaining.subtract(used); lot.shares=lot.shares.subtract(used); lot.cost=lot.cost.subtract(allocated);
                        if (lot.shares.signum()==0) lots.remove();
                    }
                    gross=money(quantity.multiply(nav.unitNav()));
                    gain=gross.subtract(released); realized=realized.add(gain); sell=sell.add(gross);
                    shares=shares.subtract(quantity); cost=cost.subtract(released);
                }
                executions.add(new Execution(order.orderId(),quantity,nav.unitNav(),gross,
                        order.side().equals("BUY") ? gross : released,gain,nav.revision(),market.source()));
            }
            BigDecimal value=precise(shares.multiply(nav.unitNav()));
            BigDecimal total=value.subtract(cost).add(realized).add(dividendGain);
            BigDecimal dayGain=(previousDate==null || calendar.next(previousDate).equals(date)) ? total.subtract(previousGain) : null;
            daily.add(new Daily(date,value,total,dayGain));
            endShares.put(date,shares);
            previousGain=total; previousDate=date; previousNav=nav;
        }
        // 非净值日的分红权益仍必须有准确除息日净值，不把它挪到后面的某天。
        for (var div : dividends) {
            if (!navs.containsKey(div.exDate()) && !div.exDate().isBefore(first)) {
                throw invalid("分红除息日净值缺失，暂不发布跨日收益。");
            }
        }
        BigDecimal paid=entitlements.values().stream().filter(DividendEntry::paid).map(DividendEntry::amount).reduce(ZERO,BigDecimal::add);
        BigDecimal received=entitlements.values().stream().map(DividendEntry::amount).reduce(ZERO,BigDecimal::add);
        Set<UUID> settled=new HashSet<>(); executions.forEach(e -> settled.add(e.orderId()));
        BigDecimal frozen=input.stream().filter(o -> o.status().equals("PENDING") && o.side().equals("SELL") && !settled.contains(o.orderId()))
                .map(Order::shares).reduce(ZERO,BigDecimal::add);
        if (shares.compareTo(frozen)<0) throw invalid("数据修正后可用份额不足，保留冻结并等待核对。");
        Nav last=navs.lastEntry().getValue(); Daily latest=daily.get(daily.size()-1);
        var position=new Position(code,name,shares,frozen,shares.subtract(frozen),cost,latest.marketValue(),
                latest.marketValue().subtract(cost),cost.signum()==0 ? null : latest.marketValue().subtract(cost).divide(cost,8,RoundingMode.HALF_UP),
                realized,received,received.subtract(paid),paid,latest.cumulativeGain(),latest.dailyGain(),buy,sell,
                last.unitNav(),last.navDate(),null);
        if (position.cumulativeGain().subtract(position.marketValue().add(sell).add(received).subtract(buy)).abs()
                .compareTo(new BigDecimal("0.00000001"))>0) throw invalid("账务核对未通过，停止发布估值。");
        return new Calculation(position,executions,daily,new ArrayList<>(entitlements.values()));
    }

    private static List<Dividend> canonicalDividends(List<Dividend> input, LocalDate first, LocalDate last) {
        var result=new TreeMap<String,Dividend>();
        for (var d : input) {
            if (d.exDate()!=null && (d.exDate().isBefore(first) || d.exDate().isAfter(last))) continue;
            if (!d.implemented()) {
                if (d.exDate()!=null && !d.exDate().isAfter(last)) throw invalid("除息事件实施状态不明，等待核对。");
                continue;
            }
            if (d.recordDate()==null || d.exDate()==null || d.payDate()==null || d.cashPerUnit()==null ||
                    d.cashPerUnit().signum()<0 || d.recordDate().isAfter(d.exDate()) || d.payDate().isBefore(d.exDate())) {
                throw invalid("现金分红关键字段不完整，等待核对。");
            }
            String key=d.recordDate()+":"+d.exDate();
            Dividend old=result.putIfAbsent(key,d);
            if (old!=null && (old.cashPerUnit().compareTo(d.cashPerUnit())!=0 || !old.payDate().equals(d.payDate()))) {
                throw invalid("同一分红事件存在冲突，等待核对。");
            }
        }
        return new ArrayList<>(result.values());
    }
    public static Calculation empty(String code,String name) {
        return new Calculation(new Position(code,name,ZERO,ZERO,ZERO,ZERO,ZERO,ZERO,null,ZERO,ZERO,ZERO,ZERO,
                ZERO,null,ZERO,ZERO,null,null,null),List.of(),List.of(),List.of());
    }
    private static Calculation pending(String code,String name,List<Order> input) {
        if (input.stream().anyMatch(o -> o.status().equals("CONFIRMED"))) throw invalid("净值或确认顺序暂不完整，保留上次结算。");
        return empty(code,name);
    }
    private static SimulationException invalid(String message) { return new SimulationException("SIM_DATA_REVIEW",message); }
}
