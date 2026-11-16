package com.fundradar.core.simulation;

import com.fundradar.core.auth.AccountRole;
import com.fundradar.core.auth.AuthenticatedUser;
import com.fundradar.core.auth.CurrentUserContext;
import com.fundradar.core.auth.PermissionCode;
import com.fundradar.core.common.web.GlobalExceptionHandler;
import com.fundradar.core.fund.api.FundCompositionResponse;
import com.fundradar.core.integration.ai.AiFundClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.fundradar.core.simulation.SimulationCompositionTypes.Composition;
import static com.fundradar.core.simulation.SimulationTypes.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 合成账户与公共报告计算；不连接数据库，不发送个人金额到资料客户端。 */
class SimulationCompositionServiceTests {
    private final UUID user = UUID.randomUUID();
    private SimulationRepository repo;
    private AiFundClient client;
    private SimulationCompositionService service;

    @BeforeEach void setup() {
        repo = mock(SimulationRepository.class);
        client = mock(AiFundClient.class);
        service = new SimulationCompositionService(repo, client);
    }

    @AfterEach void clear() { CurrentUserContext.clear(); }

    @Test void accountWeightingPreservesUnknownDenominatorsAndOverlappingThemes() {
        when(repo.positions(user)).thenReturn(List.of(position("000001", "100", "1"), position("000003", "999", "0")));
        when(repo.confirmedBaseline(user)).thenReturn(new ConfirmedBaseline(UUID.randomUUID(), null, Instant.now(),
                List.of(confirmed("000002", "200"))));
        when(client.getFundComposition("000001,000002")).thenReturn(source());

        Composition result = service.composition(user);
        assertEquals(new BigDecimal("300"), result.accountAmount());
        assertEquals(new BigDecimal("60.0000000000"), item(result, "assets", "STOCK").amount());
        assertEquals(new BigDecimal("40.0000000000"), item(result, "assets", "CASH_OTHER").amount());
        assertEquals(new BigDecimal("200"), item(result, "assets", "UNKNOWN").amount());
        assertEquals(new BigDecimal("33.333333"), result.assets().coveragePct());

        assertEquals(new BigDecimal("50.0000000000"), result.industries().totalAmount());
        assertEquals(new BigDecimal("60.000000"), item(result, "industries", "I_A").weightPct());
        assertEquals(new BigDecimal("33.333333"), result.industries().coveragePct());
        assertEquals(1, result.industries().excludedHoldings().size());

        assertEquals(new BigDecimal("100.0000000000"), result.themes().totalAmount());
        assertEquals(new BigDecimal("50.0000000000"), item(result, "themes", "CHIP").amount());
        assertEquals(new BigDecimal("50.0000000000"), item(result, "themes", "COMMUNICATION").amount());
        assertEquals(new BigDecimal("50.0000000000"), item(result, "themes", "GAME").amount());
        assertEquals(new BigDecimal("50.000000"), result.themes().mappedCoveragePct());
        assertEquals(1, item(result, "themes", "CHIP").contributions().size());
        verify(client).getFundComposition("000001,000002");
    }

    @Test void emptyAccountDoesNotCallPublicSources() {
        when(repo.positions(user)).thenReturn(List.of());
        when(repo.confirmedBaseline(user)).thenReturn(null);
        Composition result = service.composition(user);
        assertEquals(0, result.accountAmount().signum());
        assertNull(result.assets().coveragePct());
        verifyNoInteractions(client);
    }

    @Test void controllerRequiresAuthenticationAndSelfReadPermission() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new SimulationCompositionController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/api/v1/sim-portfolios/current/composition")).andExpect(status().isUnauthorized());
        CurrentUserContext.set(new AuthenticatedUser(user, "test", "测试", AccountRole.FUND_USER,
                Set.of(PermissionCode.FUND_READ)));
        mvc.perform(get("/api/v1/sim-portfolios/current/composition")).andExpect(status().isForbidden());
        verifyNoInteractions(repo);
    }

    private static SimulationCompositionTypes.Item item(Composition result, String view, String key) {
        var selected = switch (view) {
            case "assets" -> result.assets();
            case "industries" -> result.industries();
            default -> result.themes();
        };
        return selected.items().stream().filter(row -> row.key().equals(key)).findFirst().orElseThrow();
    }

    private static Position position(String code, String amount, String shares) {
        BigDecimal value = new BigDecimal(amount);
        return new Position(code, "基金" + code, new BigDecimal(shares), BigDecimal.ZERO, new BigDecimal(shares),
                value, value, BigDecimal.ZERO, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, value, BigDecimal.ZERO, BigDecimal.ONE, LocalDate.parse("2026-11-13"),
                null, false, null);
    }

    private static ConfirmedHolding confirmed(String code, String amount) {
        BigDecimal value = new BigDecimal(amount);
        return new ConfirmedHolding(code, "基金" + code, value, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private static FundCompositionResponse source() {
        LocalDate date = LocalDate.parse("2026-09-30");
        var asset = new FundCompositionResponse.AssetReport("000001", null, "资产报告", date, date, null, false,
                List.of(new FundCompositionResponse.Weight("STOCK", "股票", new BigDecimal("60")),
                        new FundCompositionResponse.Weight("CASH_OTHER", "现金及其他", new BigDecimal("40"))));
        var industry = new FundCompositionResponse.IndustryReport("000001", null, "行业报告", date, date, null, false,
                new BigDecimal("50"), List.of(new FundCompositionResponse.Weight("I_A", "行业A", new BigDecimal("30")),
                        new FundCompositionResponse.Weight("I_B", "行业B", new BigDecimal("20"))));
        List<FundCompositionResponse.ThemeAssignment> themes = List.of(
                assignment("CHIP", date), assignment("COMMUNICATION", date), assignment("GAME", date));
        var theme = new FundCompositionResponse.ThemeReport(date, date, false, new BigDecimal("100"), List.of(
                new FundCompositionResponse.ThemeHolding("X", "股票X", new BigDecimal("50"), "000001", themes),
                new FundCompositionResponse.ThemeHolding("Y", "股票Y", new BigDecimal("50"), "000001", List.of())),
                List.of(new FundCompositionResponse.ReportSource("000001", null, "主题报告", date, date, null, false)),
                false, "test");
        return new FundCompositionResponse(1, List.of(
                new FundCompositionResponse.Fund("000001", true, asset, industry, theme, null),
                new FundCompositionResponse.Fund("000002", true, null, null, null, null)),
                List.of(new FundCompositionResponse.ThemeDefinition("CHIP", "芯片", "芯片主题"),
                        new FundCompositionResponse.ThemeDefinition("COMMUNICATION", "通信", "通信主题"),
                        new FundCompositionResponse.ThemeDefinition("GAME", "游戏", "游戏主题")));
    }

    private static FundCompositionResponse.ThemeAssignment assignment(String id, LocalDate date) {
        return new FundCompositionResponse.ThemeAssignment(id, id, "测试依据", null, date, "TEST");
    }
}
