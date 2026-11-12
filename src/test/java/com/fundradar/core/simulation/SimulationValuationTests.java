package com.fundradar.core.simulation;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.fundradar.core.simulation.SimulationTypes.*;
import static com.fundradar.core.simulation.SimulationAccountingTests.*;

/** 合成账本验证收益刷新不执行交易，不连接项目数据库。 */
class SimulationValuationTests {
    OpeningBalance opening() {
        return new OpeningBalance(UUID.randomUUID(),"000001","测试基金",date("2026-09-07"),date("2026-09-04"),
                num("1"),num("1000"),num("1000"),num("1100"),num("-80"));
    }
    Valuation value(List<Order> orders,Market data) {
        return SimulationAccounting.valueConfirmed("000001","测试基金",orders,data,calendar(),date("2026-09-10"),opening());
    }
    @Test void pendingBuysAndSellsNeverExecuteEvenWhenTheirNavExists() {
        var pendingBuy=order("BUY","100","2026-09-08");
        var pendingSell=order("SELL","100","2026-09-08");
        var data=market(List.of(nav("2026-09-04","1"),nav("2026-09-07","1.01"),nav("2026-09-08","1.02")),List.of());
        var result=value(List.of(pendingBuy,pendingSell),data);
        equal("1000",result.position().shares()); equal("1100",result.position().cost());
        equal("0",result.position().totalBuy()); equal("0",result.position().totalSell());
        equal("100",result.position().frozenShares()); equal("1020",result.position().marketValue());
        equal("10",result.daily().get(1).dailyGain());
        assertEquals("PENDING",pendingBuy.status()); assertNull(pendingBuy.execution());
        assertNull(result.daily().get(0).dailyGain());
    }
    Order confirmedOrder(Order input,Execution execution) {
        return new Order(input.orderId(),input.userId(),input.fundCode(),input.fundName(),input.side(),input.amount(),input.shares(),
                input.tradeDate(),input.eligibleDate(),"CONFIRMED",input.sourceKind(),input.requestKey(),input.requestHash(),
                input.createdAt(),at("2026-09-08T22:00:00"),execution,input.ruleVersion());
    }
    @Test void confirmedFeesAndSharesAreReplayedExactlyWithoutLoadingCurrentFeeRules() {
        var buy=order("BUY","1000","2026-09-07"); var sell=order("SELL","100","2026-09-08");
        var data=market(List.of(nav("2026-09-07","1"),nav("2026-09-08","1.1"),nav("2026-09-09","1.2")),List.of());
        var original=SimulationAccounting.calculate("000001","测试基金",List.of(buy,sell),data,calendar(),date("2026-09-10"),
                Map.of("000001",new FeeSchedule(num("0.01"),List.of(new FeeBand(0,null,num("0.015"))))));
        var confirmed=List.of(confirmedOrder(buy,original.executions().get(0)),confirmedOrder(sell,original.executions().get(1)));
        var refreshed=SimulationAccounting.valueConfirmed("000001","测试基金",confirmed,data,calendar(),date("2026-09-10"),null);
        assertEquals(original.position(),refreshed.position()); assertEquals(original.daily(),refreshed.daily());
    }
    @Test void missingExecutionOrCorrectedTradeNavCannotRewriteConfirmedPositions() {
        var buy=order("BUY","1000","2026-09-07");
        var data=market(List.of(nav("2026-09-07","1"),nav("2026-09-08","1.1")),List.of());
        var original=SimulationAccounting.calculate("000001","测试基金",List.of(buy),data,calendar(),date("2026-09-10"));
        var confirmed=confirmedOrder(buy,original.executions().get(0));
        var corrected=market(List.of(nav("2026-09-07","1.01"),nav("2026-09-08","1.1")),List.of());
        assertThrows(SimulationException.class,() -> SimulationAccounting.valueConfirmed("000001","测试基金",List.of(confirmed),corrected,calendar(),date("2026-09-10"),null));
        assertThrows(SimulationException.class,() -> SimulationAccounting.valueConfirmed("000001","测试基金",List.of(confirmedOrder(buy,null)),data,calendar(),date("2026-09-10"),null));
    }
    @Test void gapsStayUnknownAndDividendEvidenceIsStillRequired() {
        var data=market(List.of(nav("2026-09-04","1"),nav("2026-09-07","1.01"),nav("2026-09-09","1.03")),List.of());
        assertNull(value(List.of(),data).daily().get(1).dailyGain());
        var unchecked=new Market(data.fundCode(),data.fundName(),true,null,data.source(),data.navs(),List.of(),null,"PENDING",null);
        assertThrows(SimulationException.class,() -> value(List.of(),unchecked));
    }
    @Test void projectionSaverCannotTouchOrdersPlansOrLedgerAndRejectsShareChangesBeforeWriting() {
        JdbcClient jdbc=mock(JdbcClient.class);
        JdbcClient.StatementSpec statement=mock(JdbcClient.StatementSpec.class,RETURNS_SELF);
        when(jdbc.sql(anyString())).thenReturn(statement); when(statement.update()).thenReturn(1);
        JdbcTemplate batch=mock(JdbcTemplate.class);
        var repo=new SimulationRepository(jdbc,batch);
        var data=market(List.of(nav("2026-09-04","1"),nav("2026-09-07","1.01"),nav("2026-09-08","1.02")),List.of());
        var next=value(List.of(),data); var previous=SimulationAccounting.opening(opening()).position();
        var state=new ValuationState(Instant.now(),List.of("2026-09-07","2026-09-08"),"CURRENT",null);
        repo.saveValuation(USER,previous,next,state,Instant.now());
        var sql=org.mockito.ArgumentCaptor.forClass(String.class); verify(jdbc).sql(sql.capture());
        assertTrue(sql.getValue().contains("UPDATE sim_position"));
        assertFalse(sql.getValue().matches("(?s).*sim_(order|plan|ledger).*"));
        verify(batch).batchUpdate(contains("INSERT INTO sim_daily_valuation"),eq(next.daily()),eq(500),any());
        clearInvocations(jdbc,batch);
        assertThrows(SimulationException.class,() -> repo.saveValuation(USER,SimulationAccounting.empty("000001","测试基金").position(),next,state,Instant.now()));
        verifyNoInteractions(jdbc,batch);
    }
    SimulationEarnings.Fund observed(List<String> dates,String status) {
        return new SimulationEarnings.Fund("000001","测试基金",date("2026-09-07"),date("2026-09-07"),false,num("100"),
                date("2026-09-07"),"REVIEW".equals(status),new ValuationState(Instant.now(),dates,status,null),date("2026-09-07"));
    }
    @Test void statusesDistinguishPublishedNavMissingNavAndUnavailableSource() {
        var fund=observed(List.of("2026-09-07","2026-09-09"),"CURRENT");
        assertEquals("VALUATION_PENDING",SimulationEarnings.missingFundStatus(date("2026-09-09"),fund,data()));
        assertEquals("NAV_MISSING",SimulationEarnings.missingFundStatus(date("2026-09-08"),fund,data()));
        assertEquals("NAV_PENDING",SimulationEarnings.missingFundStatus(date("2026-09-10"),fund,data()));
        assertEquals("PENDING",SimulationEarnings.missingFundStatus(date("2026-09-10"),observed(List.of("2026-09-07"),"RETRY"),data()));
        assertEquals("NON_TRADING",SimulationEarnings.missingFundStatus(date("2026-09-12"),fund,data()));
        assertFalse(SimulationEarnings.sourceContains(fund,date("2026-09-08")));
        assertTrue(SimulationEarnings.sourceContains(fund,date("2026-09-07")));
    }
    @Test void openingDayHasNoInventedDailyGainAndDoesNotBlockSubsequentPeriodTotal() {
        var fund=observed(List.of("2026-09-07","2026-09-08"),"CURRENT");
        var result=SimulationEarnings.summarize(fund,List.of(fund),List.of(
                new SimulationEarnings.Aggregate(date("2026-09-07"),1,0,null,num("-80")),
                new SimulationEarnings.Aggregate(date("2026-09-08"),1,1,num("10"),num("-70"))),
                date("2026-09-07"),date("2026-09-08"),data(),1,10);
        assertTrue(result.complete()); equal("10",result.periodGain());
        assertEquals("BASELINE",result.days().items().get(1).status()); assertNull(result.days().items().get(1).dailyGain());
    }
}
