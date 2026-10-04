package com.fundradar.core.simulation;

import com.fundradar.core.auth.*;
import com.fundradar.core.common.web.GlobalExceptionHandler;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.fundradar.core.simulation.SimulationEarnings.*;
import static com.fundradar.core.simulation.SimulationTypes.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 纯合成收益与模拟仓储：不连接真实账户，不运行结算、不写数据库。 */
class SimulationEarningsTests {
    final UUID user=UUID.randomUUID();
    SimulationRepository repo;
    SimulationMarketClient market;
    SimulationService service;
    MockMvc mvc;
    static LocalDate date(String text) { return LocalDate.parse(text); }
    static BigDecimal money(String text) { return text==null ? null : new BigDecimal(text); }
    static Fund fund(String code,String first,String last,boolean closed) {
        return new Fund(code,"测试基金"+code,date(first),last==null ? null : date(last),closed,money("0"),last==null ? null : date(last),false);
    }
    static CalendarData calendar() {
        return new CalendarData("synthetic",date("2026-09-01"),date("2026-09-30"),
                date("2026-09-01").datesUntil(date("2026-10-01")).filter(d -> d.getDayOfWeek().getValue()<6).toList(),"synthetic");
    }
    @BeforeEach void setup() {
        repo=mock(SimulationRepository.class); market=mock(SimulationMarketClient.class);
        service=new SimulationService(repo,market,null,Clock.fixed(Instant.parse("2026-09-30T06:00:00Z"),ZoneOffset.UTC));
        when(market.calendar()).thenReturn(calendar());
        mvc=MockMvcBuilders.standaloneSetup(new SimulationController(service)).setControllerAdvice(new GlobalExceptionHandler()).build();
        login(PermissionCode.PORTFOLIO_SELF_READ);
    }
    void login(PermissionCode... permissions) {
        CurrentUserContext.set(new AuthenticatedUser(user,"synthetic","收益测试",AccountRole.FUND_USER,Set.of(permissions)));
    }
    @AfterEach void clear() { CurrentUserContext.clear(); }

    @Test void dateAlignmentNeverAddsDifferentLatestDatesOrInventsZero() {
        var funds=List.of(fund("000001","2026-09-07","2026-09-08",false),fund("000002","2026-09-07","2026-09-07",true));
        var result=summarize(null,funds,List.of(new Aggregate(date("2026-09-07"),2,2,money("0"),money("10")),
                new Aggregate(date("2026-09-08"),1,1,money("6"),money("16"))),date("2026-09-07"),date("2026-09-09"),calendar(),1,20);
        assertNull(result.periodGain()); assertEquals(money("6"),result.knownGain());
        assertEquals("PENDING",result.days().items().get(0).status());
        assertEquals("PARTIAL",result.days().items().get(1).status());
        assertNull(result.days().items().get(1).dailyGain()); assertNull(result.days().items().get(1).cumulativeGain());
        assertEquals(money("0"),result.days().items().get(2).dailyGain());
        assertEquals(2,result.days().items().get(1).expectedFunds());
    }
    @Test void summaryUsesAllDatesAndClearedFundsWhilePagesAreDescending() {
        var funds=List.of(fund("000001","2026-09-07","2026-09-09",true));
        var values=List.of(new Aggregate(date("2026-09-07"),1,1,money("10"),money("10")),
                new Aggregate(date("2026-09-08"),1,1,money("-4"),money("6")),
                new Aggregate(date("2026-09-09"),1,1,money("0"),money("6")));
        var a=summarize(null,funds,values,date("2026-09-07"),date("2026-09-09"),calendar(),1,1);
        var b=summarize(null,funds,values,date("2026-09-07"),date("2026-09-09"),calendar(),2,1);
        assertEquals(money("6"),a.periodGain()); assertEquals(a.periodGain(),b.periodGain());
        assertEquals(3,a.days().totalCount()); assertEquals(3,a.curve().size());
        assertEquals(date("2026-09-09"),a.days().items().get(0).date());
    }
    @Test void missingGapNonTradingAndCalendarUnavailableRemainDistinct() {
        var funds=List.of(fund("000001","2026-09-11","2026-09-15",false));
        var values=List.of(new Aggregate(date("2026-09-15"),1,0,null,money("12")));
        var result=summarize(null,funds,values,date("2026-09-11"),date("2026-09-15"),calendar(),1,20);
        assertEquals(List.of("MISSING","MISSING","NON_TRADING","NON_TRADING","MISSING"),result.days().items().stream().map(Day::status).toList());
        assertNull(result.periodGain()); assertNull(result.knownGain());
        assertEquals(money("12"),result.days().items().get(0).cumulativeGain());
        assertEquals("CALENDAR_UNKNOWN",missingStatus(date("2026-09-13"),date("2026-09-15"),null));
        assertEquals("CALENDAR_UNKNOWN",missingStatus(date("2026-08-31"),null,calendar()));
    }
    @Test void noHistoryAndFutureFirstTradeAreEmptyNotProfitable() {
        assertNull(summarize(null,List.of(),List.of(),date("2026-09-01"),date("2026-09-30"),calendar(),1,20).periodGain());
        var result=summarize(null,List.of(fund("000001","2026-10-08",null,false)),List.of(),date("2026-09-01"),date("2026-09-30"),calendar(),1,20);
        assertTrue(result.days().items().isEmpty());
    }
    @Test void authenticationPermissionAndArbitraryUserIdCannotChangeOwner() throws Exception {
        CurrentUserContext.clear(); mvc.perform(get("/api/v1/sim-portfolios/current/earnings")).andExpect(status().isUnauthorized());
        login(PermissionCode.FUND_READ); mvc.perform(get("/api/v1/sim-portfolios/current/earnings/funds")).andExpect(status().isForbidden());
        verifyNoInteractions(repo);
        login(PermissionCode.PORTFOLIO_SELF_READ);
        when(repo.earningsFunds(user,null)).thenReturn(List.of());
        mvc.perform(get("/api/v1/sim-portfolios/current/earnings/funds").param("userId",UUID.randomUUID().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalCount").value(0));
        verify(repo).earningsFunds(user,null);
    }
    @Test void searchesClearedNamesAndCodesAndFiltersBeforePaging() {
        when(repo.earningsFunds(user,"000001")).thenReturn(List.of(fund("000001","2026-09-01","2026-09-30",true)));
        when(repo.earningsFunds(user,null)).thenReturn(List.of(fund("000001","2026-09-01","2026-09-30",true),fund("000002","2026-09-01","2026-09-30",false)));
        assertTrue(service.earningsFunds("000001",1,20).items().get(0).closed());
        var page=service.earningsFunds("测试基金",2,1); assertEquals(2,page.totalCount()); assertEquals("000002",page.items().get(0).fundCode());
        assertThrows(IllegalArgumentException.class,() -> service.earningsFunds("基".repeat(51),1,20));
    }
    @Test void validatesRangeCapacityAndOwnershipWithoutSilentTruncation() {
        when(repo.earningsFunds(user,null)).thenReturn(List.of(fund("000001","2010-01-01","2026-09-30",false)));
        assertThrows(IllegalArgumentException.class,() -> service.earnings(null,"ALL",null,null,1,20));
        assertThrows(IllegalArgumentException.class,() -> service.earnings(null,"CUSTOM",date("2026-09-30"),date("2026-09-01"),1,20));
        assertThrows(IllegalArgumentException.class,() -> service.earnings(null,"CUSTOM",date("2026-09-01"),date("2026-10-01"),1,20));
        assertThrows(IllegalArgumentException.class,() -> service.earnings(null,"MONTH",null,null,0,20));
        assertThrows(IllegalArgumentException.class,() -> service.earnings(null,"MONTH",null,null,1,101));
        when(repo.earningsFunds(user,"999999")).thenReturn(List.of());
        assertThrows(SimulationException.class,() -> service.earnings("999999","MONTH",null,null,1,20));
        verify(repo,never()).earningsAggregates(any(),any(),any(),any());
        when(repo.earningsFunds(user,null)).thenReturn(Collections.nCopies(2001,fund("000001","2026-09-01",null,false)));
        assertThrows(SimulationException.class,() -> service.earnings(null,"MONTH",null,null,1,20));
    }
    @Test void dateDetailsUseOneBoundedBatchAndDoNotFetchAnotherUsersHistory() {
        when(repo.earningsFunds(user,null)).thenReturn(List.of(fund("000001","2026-09-07","2026-09-07",true),fund("000002","2026-09-07","2026-09-07",false)));
        when(repo.earningsDetails(user,List.of("000002"),date("2026-09-08"))).thenReturn(Map.of());
        var result=service.earningsDetails(date("2026-09-08"),2,1);
        assertEquals(2,result.totalCount()); assertEquals("PENDING",result.items().get(0).status());
        verify(repo).earningsDetails(user,List.of("000002"),date("2026-09-08"));
    }
    @Test void defaultsAndPresetDatesAreStableWithCalendarFailure() {
        when(repo.earningsFunds(user,null)).thenReturn(List.of(fund("000001","2026-01-01","2026-09-29",false)));
        when(repo.earningsAggregates(any(),any(),any(),any())).thenReturn(List.of());
        when(market.calendar()).thenThrow(new SimulationException("UNAVAILABLE","日历不可用"));
        assertEquals(date("2026-08-31"),service.earnings(null,"MONTH",null,null,1,20).startDate());
        assertEquals(date("2026-07-01"),service.earnings(null,"QUARTER",null,null,1,20).startDate());
        assertEquals(date("2026-01-01"),service.earnings(null,"YEAR",null,null,1,20).startDate());
    }
}
