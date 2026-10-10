package com.fundradar.core.simulation;

import com.fundradar.core.integration.ai.MarketRevisionClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static com.fundradar.core.simulation.SimulationTypes.*;

/** 使用替身验证调度边界，不启动应用、不访问真实账目和行情服务。 */
class SimulationSchedulerTests {
    private final SimulationRepository repo=mock(SimulationRepository.class);
    private final SimulationMarketClient market=mock(SimulationMarketClient.class);
    private final SimulationService service=mock(SimulationService.class);
    private final MarketRevisionClient revisions=mock(MarketRevisionClient.class);
    private final Clock clock=mock(Clock.class);
    private final UUID user=UUID.randomUUID();
    private final LocalDate today=LocalDate.of(2026,10,9);
    private final Map<String,MarketRevisionClient.Revision> versions=new HashMap<>();
    private SimulationScheduler scheduler;

    @BeforeEach void setup() {
        when(clock.instant()).thenReturn(Instant.parse("2026-10-09T01:59:00Z"));
        when(repo.workRevision()).thenReturn("local-v1");
        when(repo.marketNeeds()).thenReturn(Map.of("002112",today.minusDays(10),"001021",today.minusDays(10)));
        when(repo.workerUsers(null,50)).thenReturn(List.of(user));
        when(repo.workerUsers(user,50)).thenReturn(List.of());
        when(market.calendar()).thenReturn(new CalendarData("calendar",today.minusDays(30),today.plusDays(30),
                List.of(today.minusDays(1),today,today.plusDays(3)),"test"));
        for(String code:List.of("002112","001021")) {
            versions.put(code,new MarketRevisionClient.Revision("v1",false));
            when(market.market(eq(code),any(),any())).thenReturn(new Market(code,"基金",true,null,"source",
                    List.of(),List.of(),Instant.parse("2026-10-09T01:59:00Z"),"SUCCEEDED",null));
        }
        when(revisions.read(anyList())).thenAnswer(call->new MarketRevisionClient.Snapshot("calendar-v1",Map.copyOf(versions)));
        scheduler=new SimulationScheduler(repo,market,service,mock(DataSource.class),clock,revisions);
    }

    @Test void idleTicksOnlyReadBatchVersions() {
        scheduler.run(); scheduler.run(); scheduler.run();
        verify(market,times(1)).calendar();
        verify(market,times(2)).market(anyString(),any(),any());
        verify(service,times(1)).processUser(eq(user),any(),anyMap(),any());
        verify(market,never()).refresh(anyList());
    }

    @Test void changedFundReloadsOnlyItsHistory() {
        scheduler.run(); versions.put("002112",new MarketRevisionClient.Revision("revised",true)); scheduler.run();
        verify(market,times(2)).market(eq("002112"),any(),any());
        verify(market,times(1)).market(eq("001021"),any(),any());
        verify(service,times(2)).processUser(eq(user),any(),anyMap(),any());
    }

    @Test void newOrderOrFeeChangeProcessesWithUnchangedMarket() {
        scheduler.run(); when(repo.workRevision()).thenReturn("new-order-or-fee"); scheduler.run();
        verify(service,times(2)).processUser(eq(user),any(),anyMap(),any());
        verify(market,times(2)).market(anyString(),any(),any());
    }

    @Test void tenOClockAndCutoffWakePlansWithoutReloadingHistory() {
        scheduler.run();
        when(clock.instant()).thenReturn(Instant.parse("2026-10-09T02:00:00Z")); scheduler.run();
        scheduler.run();
        when(clock.instant()).thenReturn(Instant.parse("2026-10-09T07:00:00Z")); scheduler.run();
        verify(service,times(3)).processUser(eq(user),any(),anyMap(),any());
        verify(market,times(2)).market(anyString(),any(),any());
    }

    @Test void accountFailureDoesNotAdvanceCheckpoint() {
        doThrow(new IllegalStateException("temporary")).doNothing().when(service).processUser(eq(user),any(),anyMap(),any());
        scheduler.run(); scheduler.run(); scheduler.run();
        verify(service,times(2)).processUser(eq(user),any(),anyMap(),any());
        verify(market,times(2)).market(anyString(),any(),any());
    }

    @Test void failedMarketReadDoesNotReuseStaleFundAndRetries() {
        scheduler.run(); versions.put("002112",new MarketRevisionClient.Revision("revised",true));
        when(market.market(eq("002112"),any(),any())).thenThrow(new IllegalStateException("temporary"))
                .thenReturn(new Market("002112","基金",true,null,"source",List.of(),List.of(),Instant.parse("2026-10-09T01:59:00Z"),"SUCCEEDED",null));
        scheduler.run(); scheduler.run();
        verify(service).processUser(eq(user),any(),argThat(data->!data.containsKey("002112")),any());
        verify(market,times(3)).market(eq("002112"),any(),any());
    }

    @Test void noAccountsNeedMarketMeansNoPythonCalls() {
        when(repo.marketNeeds()).thenReturn(Map.of()); scheduler.run();
        verifyNoInteractions(revisions,market,service);
    }

    @Test void changedIssueAndDayOrCalendarStillWakeWork() {
        scheduler.run();
        // 某轮先因旧问题跳过定投，再结算清除问题；下一轮必须重新执行到期计划。
        when(repo.workRevision()).thenReturn("issue-cleared"); scheduler.run();
        verify(service,times(2)).processUser(eq(user),any(),anyMap(),any());
        when(revisions.read(anyList())).thenReturn(new MarketRevisionClient.Snapshot("calendar-v2",Map.copyOf(versions)));
        scheduler.run(); verify(market,times(2)).calendar();
        when(clock.instant()).thenReturn(Instant.parse("2026-10-10T01:59:00Z")); scheduler.run();
        verify(market,times(2)).market(eq("002112"),any(),any());
        verify(service,times(4)).processUser(eq(user),any(),anyMap(),any());
    }

    @Test void largeScopesAreSplitIntoBatchesOfAtMostFifty() {
        Map<String,LocalDate> needs=new LinkedHashMap<>();
        for(int i=0;i<51;i++) needs.put("%06d".formatted(i),today.minusDays(10));
        when(repo.marketNeeds()).thenReturn(needs);
        when(revisions.read(anyList())).thenAnswer(call->{
            List<MarketRevisionClient.Query> qs=call.getArgument(0);
            org.junit.jupiter.api.Assertions.assertTrue(qs.size()<=50);
            Map<String,MarketRevisionClient.Revision> values=new HashMap<>();
            for(var q:qs) values.put(q.key(),new MarketRevisionClient.Revision("v1",false));
            return new MarketRevisionClient.Snapshot("calendar-v1",values);
        });
        scheduler.run(); verify(revisions,times(2)).read(anyList());
    }
}
