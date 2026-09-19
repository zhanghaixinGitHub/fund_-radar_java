package com.fundradar.core.simulation;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.fundradar.core.simulation.SimulationTypes.*;

class SimulationAccountingTests {
    static final UUID USER=UUID.randomUUID();
    static LocalDate date(String value) { return LocalDate.parse(value); }
    static BigDecimal num(String value) { return new BigDecimal(value); }
    static Instant at(String value) { return LocalDateTime.parse(value).atZone(SimulationCalendar.ZONE).toInstant(); }
    static CalendarData data() {
        LocalDate start=date("2026-01-01"),end=date("2026-12-31");
        var holidays=Set.of(date("2026-10-01"),date("2026-10-02"),date("2026-10-05"),date("2026-10-06"),date("2026-10-07"));
        return new CalendarData("TEST_VERIFIED",start,end,start.datesUntil(end.plusDays(1))
                .filter(d -> d.getDayOfWeek().getValue()<=5 && !holidays.contains(d)).toList(),"测试日历");
    }
    static SimulationCalendar calendar() { return new SimulationCalendar(data()); }
    static Nav nav(String day,String value) { return new Nav(date(day),num(value),num(value),date(day),day+value); }
    static Market market(List<Nav> navs,List<Dividend> dividends) {
        return new Market("000001","测试普通混合基金",true,null,"TUSHARE_PRO_FUND",navs,dividends,
                at("2026-12-30T22:00:00"),"SUCCEEDED",null);
    }
    static Order order(String side,String amount,String day) {
        return order(side,amount,day,"PENDING");
    }
    static Order order(String side,String amount,String day,String status) {
        LocalDate trade=date(day);
        return new Order(UUID.randomUUID(),USER,"000001","测试普通混合基金",side,side.equals("BUY") ? num(amount) : null,
                side.equals("SELL") ? num(amount) : null,trade,calendar().next(trade),status,"MANUAL",
                UUID.randomUUID().toString(),"hash",trade.atTime(11,0).atZone(SimulationCalendar.ZONE).toInstant(),
                status.equals("CONFIRMED") ? trade.atTime(21,0).atZone(SimulationCalendar.ZONE).toInstant() : null,null,"CN_NAV_SIM_V2_FEE");
    }
    /** 构造已确认订单，用于模拟历史确认过的持仓（重放确认只关心订单状态与净值）。 */
    static Order confirmed(String side,String amount,String day) { return order(side,amount,day,"CONFIRMED"); }
    static Calculation calculate(List<Order> orders,Market market,String today) {
        return calculate(orders,market,today,Map.of());
    }
    static Calculation calculate(List<Order> orders,Market market,String today,Map<String,FeeSchedule> fees) {
        return SimulationAccounting.calculate("000001","测试普通混合基金",orders,market,calendar(),date(today),fees);
    }
    static void equal(String expected,BigDecimal actual) { assertNotNull(actual); assertEquals(0,num(expected).compareTo(actual),"expected="+expected+" actual="+actual); }

    @Test void buyAddPartialAndFullSellPreserveFifoAndRealizedGain() {
        var orders=new ArrayList<>(List.of(order("BUY","1000","2026-09-07"),order("BUY","110","2026-09-08"),order("SELL","500","2026-09-09")));
        var market=market(List.of(nav("2026-09-07","1"),nav("2026-09-08","1.1"),nav("2026-09-09","1.1"),nav("2026-09-10","1.1")),List.of());
        var result=calculate(orders,market,"2026-09-10");
        equal("600",result.position().shares()); equal("610",result.position().cost()); equal("660",result.position().marketValue());
        equal("50",result.position().realizedGain()); equal("50",result.position().holdingGain()); equal("100",result.position().cumulativeGain());
        equal("0",result.daily().get(2).dailyGain());
        orders.add(order("SELL","600","2026-09-10"));
        var closed=calculate(orders,market,"2026-09-11").position();
        equal("0",closed.shares()); equal("0",closed.cost()); equal("100",closed.realizedGain()); equal("100",closed.cumulativeGain());
    }
    @Test void dividendExDateEntitlementSurvivesSaleAndPayDateDoesNotCountTwice() {
        var div=new Dividend("cash",date("2026-09-08"),date("2026-09-08"),date("2026-09-10"),num("0.1"),true,"v1");
        var m=market(List.of(nav("2026-09-07","1"),new Nav(date("2026-09-08"),num("0.9"),num("1"),date("2026-09-08"),"v2")),List.of(div));
        var orders=List.of(order("BUY","1000","2026-09-07"),order("SELL","1000","2026-09-08"));
        var before=calculate(orders,m,"2026-09-09").position();
        equal("100",before.receivableDividend()); equal("0",before.paidDividend()); equal("-100",before.realizedGain()); equal("0",before.cumulativeGain());
        var after=calculate(orders,m,"2026-09-10").position();
        equal("0",after.receivableDividend()); equal("100",after.paidDividend()); equal("0",after.cumulativeGain());
        var exBuyer=calculate(List.of(order("BUY","900","2026-09-08")),m,"2026-09-09").position();
        equal("0",exBuyer.dividendGain()); equal("0",exBuyer.cumulativeGain());
    }
    @Test void contributionsAreNotProfitAndGapsDoNotInventDailyReturns() {
        var m=market(List.of(nav("2026-09-07","1"),nav("2026-09-09","1.1")),List.of());
        var p=calculate(List.of(order("BUY","1000","2026-09-07"),order("BUY","1100","2026-09-09")),m,"2026-09-10").position();
        equal("100",p.cumulativeGain()); equal("2200",p.marketValue()); assertNull(p.dailyGain());
    }
    @Test void pendingOrdersConfirmWhenNavPublishedWithoutWaitingEligibleDay() {
        var buy=order("BUY","1000","2026-09-07");
        // 当日净值未公布：继续等待
        assertTrue(calculate(List.of(buy),market(List.of(nav("2026-09-08","1")),List.of()),"2026-09-09").executions().isEmpty());
        // 当日净值当晚公布：当晚即确认，不再等待 eligibleDate（9-08）
        assertEquals(1,calculate(List.of(buy),market(List.of(nav("2026-09-07","1")),List.of()),"2026-09-07").executions().size());
        assertEquals(1,calculate(List.of(buy),market(List.of(nav("2026-09-07","1")),List.of()),"2026-09-08").executions().size());
    }
    @Test void publishedNavConfirmsPlanOrderAndValuationAdvancesSameEvening() {
        // 复现 9-18 场景：9-07 已确认持仓，9-08 定投订单在 9-08 净值当晚公布后应立即确认并更新当日估值
        var buy=confirmed("BUY","1000","2026-09-07");
        var pending=order("BUY","1100","2026-09-08");
        var m=market(List.of(nav("2026-09-07","1"),nav("2026-09-08","1.1")),List.of());
        var result=calculate(List.of(buy,pending),m,"2026-09-08");
        assertEquals(2,result.executions().size());
        assertEquals(date("2026-09-08"),result.position().navDate());
        equal("2000",result.position().shares());
        equal("2200",result.position().marketValue());
        equal("100",result.position().dailyGain());
    }
    @Test void pendingOrderWithoutPublishedNavDoesNotFreezeLaterValuation() {
        // 订单净值未公布时估值照常推进到最新净值日，不因订单未确认而冻结
        var buy=confirmed("BUY","1000","2026-09-07");
        var stuck=order("BUY","1100","2026-09-08");
        var m=market(List.of(nav("2026-09-07","1"),nav("2026-09-10","1.2")),List.of());
        var result=calculate(List.of(buy,stuck),m,"2026-09-10");
        assertEquals(1,result.executions().size());
        assertEquals(date("2026-09-10"),result.position().navDate());
        equal("1000",result.position().shares());
        equal("1200",result.position().marketValue());
        equal("200",result.position().cumulativeGain());
    }
    @Test void purchaseFeeReducesSharesAndRecordsFee() {
        var m=market(List.of(nav("2026-09-07","1")),List.of());
        var fees=Map.of("000001",new FeeSchedule(num("0.0008"),List.of()));
        var result=calculate(List.of(order("BUY","100","2026-09-07")),m,"2026-09-07",fees);
        var execution=result.executions().get(0);
        equal("99.92006395",execution.netAmount());
        equal("99.92006395",execution.shares());
        equal("0.08",execution.fee());
        equal("100",result.position().cost());
        equal("100",result.position().totalBuy());
    }
    @Test void redeemFeeBandsFollowFifoHoldingDays() {
        var fees=Map.of("000001",new FeeSchedule(SimulationAccounting.ZERO,List.of(
                new FeeBand(0,6,num("0.015")),new FeeBand(7,29,num("0.005")),new FeeBand(30,null,SimulationAccounting.ZERO))));
        var buy=confirmed("BUY","1000","2026-09-07");
        // 9-09 卖出：持有 2 天 → 1.5%
        var twoDays=calculate(List.of(buy,order("SELL","500","2026-09-09")),
                market(List.of(nav("2026-09-07","1"),nav("2026-09-09","1.1")),List.of()),"2026-09-09",fees).executions().get(1);
        equal("550",twoDays.grossAmount()); equal("8.25",twoDays.fee()); equal("541.75",twoDays.netAmount());
        // 9-14 卖出：持有 7 天 → 0.5%
        var sevenDays=calculate(List.of(buy,order("SELL","500","2026-09-14")),
                market(List.of(nav("2026-09-07","1"),nav("2026-09-14","1.1")),List.of()),"2026-09-14",fees).executions().get(1);
        equal("2.75",sevenDays.fee()); equal("547.25",sevenDays.netAmount());
        // 10-07 卖出：持有 30 天 → 0
        var thirtyDays=calculate(List.of(buy,order("SELL","500","2026-10-07")),
                market(List.of(nav("2026-09-07","1"),nav("2026-10-07","1.1")),List.of()),"2026-10-07",fees).executions().get(1);
        equal("0",thirtyDays.fee()); equal("550",thirtyDays.netAmount());
    }
    @Test void confirmedSharesBecomeSellableOnlyFromNextTradingDay() {
        var buy=order("BUY","1000","2026-09-07");
        var m=market(List.of(nav("2026-09-07","1")),List.of());
        var sameDay=calculate(List.of(buy),m,"2026-09-07").position();
        equal("1000",sameDay.shares()); equal("0",sameDay.availableShares());
        var nextDay=calculate(List.of(buy),m,"2026-09-08").position();
        equal("1000",nextDay.availableShares());
    }
    @Test void revisedNavRebuildsSharesAndRejectsImpossibleHistoricalSale() {
        var buy=order("BUY","1000","2026-09-07");
        var m=market(List.of(nav("2026-09-07","1.25"),nav("2026-09-08","1.375")),List.of());
        var result=calculate(List.of(buy),m,"2026-09-09");
        equal("800",result.position().shares()); equal("100",result.position().cumulativeGain());
        assertThrows(SimulationException.class,() -> calculate(List.of(buy,order("SELL","900","2026-09-08")),m,"2026-09-09"));
    }
    @Test void missingOrConflictingDividendCannotPublishFakeLoss() {
        var buy=order("BUY","1000","2026-09-07");
        var navs=List.of(nav("2026-09-07","1"),new Nav(date("2026-09-08"),num("0.9"),num("1"),date("2026-09-08"),"cash"));
        assertThrows(SimulationException.class,() -> calculate(List.of(buy),market(navs,List.of()),"2026-09-09"));
        var a=new Dividend("a",date("2026-09-08"),date("2026-09-08"),date("2026-09-10"),num("0.1"),true,"a");
        var b=new Dividend("b",a.recordDate(),a.exDate(),a.payDate(),num("0.2"),true,"b");
        assertThrows(SimulationException.class,() -> calculate(List.of(buy),market(navs,List.of(a,b)),"2026-09-09"));
        var duplicate=new Dividend("b",a.recordDate(),a.exDate(),a.payDate(),a.cashPerUnit(),true,"b");
        equal("100",calculate(List.of(buy),market(navs,List.of(a,duplicate)),"2026-09-09").position().dividendGain());
    }
    @Test void calendarCutoffsHolidaysMonthEndAndCoverageAreExplicit() {
        var c=calendar();
        assertEquals(date("2026-09-11"),c.tradeDate(at("2026-09-11T14:59:59")));
        assertEquals(date("2026-09-14"),c.tradeDate(at("2026-09-11T15:00:00")));
        assertEquals(date("2026-10-08"),c.tradeDate(at("2026-09-30T15:00:00")));
        assertEquals(date("2026-02-28"),c.scheduled("MONTHLY",31,date("2026-02-01")));
        assertEquals(date("2026-03-31"),c.nextScheduled("MONTHLY",31,date("2026-02-28")));
        assertEquals(date("2026-09-11"),c.firstPlanBase(date("2026-09-01"),at("2026-09-10T10:00:00")));
        assertThrows(SimulationException.class,() -> c.next(date("2026-12-31")));
    }
}
