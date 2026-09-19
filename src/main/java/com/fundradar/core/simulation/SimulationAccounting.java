package com.fundradar.core.simulation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static com.fundradar.core.simulation.SimulationTypes.*;

/** 可重放的十进制模拟账务：先进先出、现金分红、净值更正和资金流中性收益；
 *  订单按净值公布即确认（不再等待确认日），申赎费用按订单规则版本计收。 */
public final class SimulationAccounting {
    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(8);
    private SimulationAccounting() {}
    public static BigDecimal precise(BigDecimal value) { return value.setScale(8,RoundingMode.HALF_UP); }
    private static BigDecimal money(BigDecimal value) { return value.setScale(2,RoundingMode.HALF_UP); }
    /** 单个买入批次；确认日用于赎回费持有天数分档，可用日用于限制卖出（份额 T+1 交易日可用）。 */
    private static class Lot {
        BigDecimal shares, cost;
        LocalDate confirmedOn, availableOn;
        Lot(BigDecimal shares, BigDecimal cost, LocalDate confirmedOn, LocalDate availableOn) {
            this.shares=shares; this.cost=cost; this.confirmedOn=confirmedOn; this.availableOn=availableOn;
        }
    }
    /** 申购费率：无规则时为零（C 类免申购费）。 */
    private static BigDecimal purchaseRate(FeeSchedule schedule) {
        return schedule==null || schedule.purchaseRate()==null ? ZERO : schedule.purchaseRate();
    }
    /** 赎回费率：按持有自然天数匹配分档，无规则或无匹配档时为零。 */
    private static BigDecimal redeemRate(FeeSchedule schedule, long holdingDays) {
        if (schedule==null || schedule.redeem()==null) return ZERO;
        for (var band : schedule.redeem()) {
            if (holdingDays>=band.minDays() && (band.maxDays()==null || holdingDays<=band.maxDays())) return band.rate();
        }
        return ZERO;
    }

    public static Calculation calculate(String code, String name, List<Order> input, Market market,
                                        SimulationCalendar calendar, LocalDate today) {
        return calculate(code,name,input,market,calendar,today,Map.of());
    }
    public static Calculation calculate(String code, String name, List<Order> input, Market market,
                                        SimulationCalendar calendar, LocalDate today, Map<String,FeeSchedule> fees) {
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
        // 订单按 tradeDate 顺序连续确认：净值公布（navs 已过滤 announcedOn<=today）即按 T 日净值确认，
        // 不再等待 eligibleDate；eligibleDate 仅作为份额可用日（T+1 交易日可卖）。
        // 遇到第一个净值未公布的 PENDING 时，本单及之后订单按顺序等待，但估值曲线不冻结，
        // 仍推进到最新已公布净值日——订单确认与每日估值互不阻塞。
        boolean blocked = false;
        for (var order : orders) {
            if (order.status().equals("CONFIRMED")) {
                if (!navs.containsKey(order.tradeDate())) throw invalid("已确认交易的净值缺失，保留上次估值等待核对。");
                applied.add(order);
            } else if (!blocked && navs.containsKey(order.tradeDate())) {
                applied.add(order);
            } else {
                blocked = true;
            }
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
                // 费用口径跟随订单规则版本：V1 历史订单重放保持无费，V2 订单计申购费与分档赎回费。
                boolean v2=RULE_V2.equals(order.ruleVersion());
                BigDecimal fee=null,net=null;
                if (order.side().equals("BUY")) {
                    BigDecimal rate=v2 ? purchaseRate(fees.getOrDefault(code,new FeeSchedule(ZERO,List.of()))) : ZERO;
                    BigDecimal netAmount=order.amount();
                    fee=ZERO;
                    if (rate.signum()>0) {
                        // 净申购金额 = 申购金额 ÷ (1+申购费率)；申购费 = 申购金额 − 净申购金额（与支付宝口径一致）
                        netAmount=precise(order.amount().divide(BigDecimal.ONE.add(rate),8,RoundingMode.HALF_UP));
                        fee=money(order.amount().subtract(netAmount));
                    }
                    quantity=netAmount.divide(nav.unitNav(),8,RoundingMode.DOWN);
                    if (quantity.signum()<=0) throw invalid("买入金额不足以形成有效模拟份额。");
                    gross=order.amount();
                    // 成本记全额投入（本金口径），费用体现在份额减少上；确认日=净值归属日，可用日=次一交易日
                    lots.add(new Lot(quantity,gross,date,order.eligibleDate()));
                    shares=shares.add(quantity); cost=cost.add(gross); buy=buy.add(gross);
                    net=v2 ? netAmount : null; fee=v2 ? fee : null;
                } else {
                    quantity=order.shares();
                    if (quantity.compareTo(shares)>0) throw invalid("历史净值修正后卖出份额不足，需核对已有交易。");
                    BigDecimal remaining=quantity;
                    BigDecimal feeTotal=ZERO;
                    while (remaining.signum()>0) {
                        Lot lot=lots.peek();
                        if (lot==null) throw invalid("份额批次不足，停止结算。");
                        if (lot.availableOn.isAfter(date)) throw invalid("存在未到可用日的批次被卖出，等待数据核对。");
                        BigDecimal used=remaining.min(lot.shares);
                        BigDecimal allocated=used.compareTo(lot.shares)==0 ? lot.cost :
                                lot.cost.multiply(used).divide(lot.shares,8,RoundingMode.HALF_UP);
                        released=released.add(allocated);
                        if (v2) {
                            // 赎回费按批次持有自然天数分档，逐批计算后从赎回净额中扣减
                            long days=ChronoUnit.DAYS.between(lot.confirmedOn,date);
                            feeTotal=feeTotal.add(money(used.multiply(nav.unitNav()).multiply(redeemRate(fees.getOrDefault(code,new FeeSchedule(ZERO,List.of())),days))));
                        }
                        remaining=remaining.subtract(used); lot.shares=lot.shares.subtract(used); lot.cost=lot.cost.subtract(allocated);
                        if (lot.shares.signum()==0) lots.remove();
                    }
                    gross=money(quantity.multiply(nav.unitNav()));
                    BigDecimal netAmount=gross.subtract(feeTotal);
                    gain=netAmount.subtract(released); realized=realized.add(gain); sell=sell.add(netAmount);
                    shares=shares.subtract(quantity); cost=cost.subtract(released);
                    net=v2 ? netAmount : null; fee=v2 ? money(feeTotal) : null;
                }
                executions.add(new Execution(order.orderId(),quantity,nav.unitNav(),gross,
                        order.side().equals("BUY") ? gross : released,gain,nav.revision(),market.source(),fee,net,
                        v2 ? order.ruleVersion() : null));
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
        // 可用份额只含已到可用日的批次（份额 T+1 交易日可用）；当前冻结的卖出在仓储层按订单实时扣除。
        BigDecimal available=ZERO;
        for (var lot : lots) if (!lot.availableOn.isAfter(today)) available=available.add(lot.shares);
        Nav last=navs.lastEntry().getValue(); Daily latest=daily.get(daily.size()-1);
        var position=new Position(code,name,shares,frozen,available,cost,latest.marketValue(),
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
