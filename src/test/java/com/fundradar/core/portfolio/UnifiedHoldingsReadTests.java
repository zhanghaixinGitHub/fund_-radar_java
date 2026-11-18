package com.fundradar.core.portfolio;

import com.fundradar.core.auth.*;
import com.fundradar.core.integration.ai.AiFundClient;
import com.fundradar.core.simulation.SimulationRepository;
import com.fundradar.core.simulation.SimulationTypes.Position;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.fundradar.core.portfolio.AccountFundingTypes.Scope;

class UnifiedHoldingsReadTests {
    final UUID user=UUID.randomUUID();
    final JdbcClient db=mock(JdbcClient.class);
    final SimulationRepository positions=mock(SimulationRepository.class);
    final AiFundClient funds=mock(AiFundClient.class);
    final AccountRiskService service=new AccountRiskService(db,positions,funds);
    @BeforeEach void login() {
        CurrentUserContext.set(new AuthenticatedUser(user,"test","测试",AccountRole.FUND_USER,Set.of(PermissionCode.PORTFOLIO_SELF_READ)));
    }
    @AfterEach void clear() { CurrentUserContext.clear(); }
    Position position(String code,String shares,BigDecimal amount) {
        var p=mock(Position.class);
        when(p.fundCode()).thenReturn(code);when(p.fundName()).thenReturn("测试基金");
        when(p.shares()).thenReturn(new BigDecimal(shares));when(p.marketValue()).thenReturn(amount);
        when(p.navDate()).thenReturn(LocalDate.of(2026,11,16));return p;
    }
    @Test void bothLegacyLinksUseCurrentPositiveShareLedgerWithoutSnapshotQueries() {
        var rows=List.of(position("000001","10",new BigDecimal("123")),position("000002","0",new BigDecimal("999")));
        when(positions.positions(user)).thenReturn(rows);
        for(var scope:Scope.values()) {
            var result=service.read(scope,new BigDecimal("10"));
            assertEquals(Scope.SIMULATED,result.scope());assertEquals("我的持仓",result.scopeDescription());
            assertEquals(1,result.holdings().size());assertEquals(new BigDecimal("123"),result.recordedAmount());
            assertEquals(new BigDecimal("12.30"),result.scenarioLoss());
        }
        verifyNoInteractions(db,funds);
    }
    @Test void missingValuationKeepsHoldingAndDoesNotInventWholeAccountAmount() {
        var rows=List.of(position("000001","10",null),position("000002","2",new BigDecimal("20")));
        when(positions.positions(user)).thenReturn(rows);
        var result=service.read(Scope.CONFIRMED,new BigDecimal("10"));
        assertEquals(2,result.holdings().size());assertNull(result.recordedAmount());assertNull(result.scenarioLoss());
        verifyNoInteractions(db,funds);
    }
}
