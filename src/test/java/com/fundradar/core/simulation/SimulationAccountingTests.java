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
        LocalDate trade=date(day);
        return new Order(UUID.randomUUID(),USER,"000001","测试普通混合基金",side,side.equals("BUY") ? num(amount) : null,
                side.equals("SELL") ? num(amount) : null,trade,calendar().next(trade),"PENDING","MANUAL",
                UUID.randomUUID().toString(),"hash",trade.atTime(11,0).atZone(SimulationCalendar.ZONE).toInstant(),null,null);
    }
    static Calculation calculate(List<Order> orders,Market market,String today) {
        return SimulationAccounting.calculate("000001","测试普通混合基金",orders,market,calendar(),date(today));
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
    @Test void pendingOrdersRequireExactNavAndEligibleDay() {
        var buy=order("BUY","1000","2026-09-07");
        assertTrue(calculate(List.of(buy),market(List.of(nav("2026-09-08","1")),List.of()),"2026-09-09").executions().isEmpty());
        assertTrue(calculate(List.of(buy),market(List.of(nav("2026-09-07","1")),List.of()),"2026-09-07").executions().isEmpty());
        assertEquals(1,calculate(List.of(buy),market(List.of(nav("2026-09-07","1")),List.of()),"2026-09-08").executions().size());
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
