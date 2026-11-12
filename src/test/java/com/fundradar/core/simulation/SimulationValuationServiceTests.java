package com.fundradar.core.simulation;

import com.fundradar.core.integration.ai.MarketRevisionClient;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.fundradar.core.simulation.SimulationTypes.*;
import static com.fundradar.core.simulation.SimulationAccountingTests.*;

/** 使用合成行情和事务替身验证内容变化刷新、失败重试及交易隔离。 */
class SimulationValuationServiceTests {
    SimulationRepository repo=mock(SimulationRepository.class);
    SimulationMarketClient market=mock(SimulationMarketClient.class);
    MarketRevisionClient revisions=mock(MarketRevisionClient.class);
    TransactionTemplate tx=mock(TransactionTemplate.class);
    SimulationValuationService service;
    Map<String,MarketRevisionClient.Revision> versions=new HashMap<>();
    OpeningBalance opening=new OpeningBalance(UUID.randomUUID(),"000001","测试基金",date("2026-09-07"),date("2026-09-04"),
            num("1"),num("1000"),num("1000"),num("1100"),num("-80"));
    @BeforeEach void setup() {
        service=new SimulationValuationService(repo,market,revisions,tx,Clock.fixed(at("2026-09-10T12:00:00"),ZoneOffset.UTC));
        when(tx.execute(any())).thenAnswer(call -> ((TransactionCallback<?>)call.getArgument(0)).doInTransaction(new SimpleTransactionStatus()));
        doAnswer(call -> { ((java.util.function.Consumer<org.springframework.transaction.TransactionStatus>)call.getArgument(0)).accept(new SimpleTransactionStatus()); return null; }).when(tx).executeWithoutResult(any());
        when(repo.valuationNeeds(USER)).thenReturn(Map.of("000001",date("2026-09-04")));
        when(repo.valuationRevision(USER)).thenReturn("confirmed-book-v1");
        when(repo.positions(USER)).thenReturn(List.of(SimulationAccounting.opening(opening).position()));
        when(repo.openingBalances(USER)).thenReturn(Map.of("000001",opening));
        when(repo.orders(USER,"000001")).thenReturn(List.of(order("BUY","200","2026-09-08")));
        when(market.calendar()).thenReturn(data());
        when(market.market(eq("000001"),any(),any())).thenReturn(market(List.of(nav("2026-09-04","1"),nav("2026-09-07","1.01"),nav("2026-09-08","1.02")),List.of()));
        versions.put("000001",new MarketRevisionClient.Revision("v1",true));
        when(revisions.read(anyList())).thenAnswer(call -> new MarketRevisionClient.Snapshot("calendar-v1",Map.copyOf(versions)));
    }
    @Test void unchangedNavDoesNotWriteAgainButNewNavAutomaticallyRefreshes() {
        assertEquals(1,service.refresh(USER).updated());
        assertTrue(service.refresh(USER).unchanged());
        versions.put("000001",new MarketRevisionClient.Revision("new-nav",true));
        assertEquals(1,service.refresh(USER).updated());
        verify(market,times(2)).market(eq("000001"),any(),any());
        verify(repo,times(2)).saveValuation(eq(USER),any(),any(),any(),any());
        verify(repo,never()).plans(any()); verify(repo,never()).loadFees();
        verify(repo,never()).saveCalculation(any(),any(),any()); verify(repo,never()).savePlan(any());
        verify(repo,never()).insertOrder(any()); verify(repo,never()).ledger(any(),any(),any(),any(),any(),any());
    }
    @Test void newConfirmedTradeIsDetectedWithoutReloadingUnchangedMarket() {
        service.refresh(USER); when(repo.valuationRevision(USER)).thenReturn("confirmed-book-v2");
        service.refresh(USER);
        verify(market,times(1)).market(eq("000001"),any(),any());
        verify(repo,times(2)).saveValuation(eq(USER),any(),any(),any(),any());
    }
    @Test void marketFailureKeepsOldIncomeAndRetriesWithoutAdvancingCheckpoint() {
        when(market.market(eq("000001"),any(),any())).thenThrow(new SimulationException("SIM_UNAVAILABLE","暂不可用"))
                .thenReturn(market(List.of(nav("2026-09-04","1"),nav("2026-09-07","1.01")),List.of()));
        assertEquals(1,service.refresh(USER).waiting());
        verify(repo,never()).saveValuation(any(),any(),any(),any(),any());
        verify(repo).valuationState(eq(USER),eq("000001"),argThat(s -> "RETRY".equals(s.status())&&s.navDates()==null));
        assertEquals(1,service.refresh(USER).updated()); assertTrue(service.refresh(USER).unchanged());
    }
    @Test void invalidFinancialEvidenceOnlySetsValuationReviewAndNeverSettlesOrders() {
        doThrow(new SimulationException("SIM_DATA_REVIEW","份额待核对")).when(repo).saveValuation(any(),any(),any(),any(),any());
        assertEquals(1,service.refresh(USER).waiting());
        verify(repo).valuationState(eq(USER),eq("000001"),argThat(s -> "REVIEW".equals(s.status())&&"份额待核对".equals(s.message())));
        verify(repo,never()).issue(any(),any(),any(),any()); verify(repo,never()).saveCalculation(any(),any(),any());
    }
    @Test void noConfirmedHoldingsMeansNoMarketCallsOrWrites() {
        when(repo.valuationNeeds(USER)).thenReturn(Map.of()); assertTrue(service.refresh(USER).unchanged());
        verifyNoInteractions(market,revisions,tx);
    }
}
