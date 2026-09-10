package com.fundradar.core.watchlist.service;

import com.fundradar.core.auth.AccountRole;
import com.fundradar.core.auth.AuthenticatedUser;
import com.fundradar.core.auth.CurrentUserContext;
import com.fundradar.core.auth.PermissionCode;
import com.fundradar.core.integration.ai.AiFundClient;
import com.fundradar.core.integration.ai.AiFundSummary;
import com.fundradar.core.integration.ai.AiServiceUnavailableException;
import com.fundradar.core.watchlist.api.WatchlistQuotaResponse;
import com.fundradar.core.watchlist.credit.WatchlistCreditService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 使用合成 JDBC 结果和行情替身验证筛选后分页，不连接实际账号库或行情服务。 */
class JdbcWatchlistSearchTests {
    private final AuthenticatedUser user = new AuthenticatedUser(UUID.randomUUID(), "synthetic-user", "测试用户",
            AccountRole.FUND_USER, Set.of(PermissionCode.WATCHLIST_SELF_READ));
    private final AiFundClient ai = mock(AiFundClient.class);
    private final WatchlistCreditService credit = mock(WatchlistCreditService.class);
    private final Map<String, Object> parameters = new HashMap<>();
    private final List<List<String>> batches = new ArrayList<>();
    private final List<String> codes = IntStream.rangeClosed(1, 52).mapToObj(i -> "%06d".formatted(i)).toList();
    private JdbcWatchlistService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        CurrentUserContext.set(user);
        var jdbc = mock(JdbcClient.class);
        var statement = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(anyString())).thenAnswer(call -> {
            // 所有关注 SQL 都必须携带本人条件，分页和计数使用同一条件。
            assertTrue(call.getArgument(0, String.class).contains("user_id = :userId"));
            parameters.clear();
            return statement;
        });
        when(statement.param(anyString(), any())).thenAnswer(call -> {
            parameters.put(call.getArgument(0), call.getArgument(1));
            return statement;
        });
        var missing = mock(JdbcClient.MappedQuerySpec.class);
        when(missing.list()).thenReturn(List.of());
        when(statement.query(String.class)).thenReturn(missing);
        var count = mock(JdbcClient.MappedQuerySpec.class);
        when(count.single()).thenAnswer(call -> (long) scopedCodes().size());
        when(statement.query(Long.class)).thenReturn(count);
        when(statement.query(any(RowMapper.class))).thenAnswer(call -> {
            RowMapper<?> mapper = call.getArgument(0);
            List<String> scoped = scopedCodes();
            int offset = ((Number) parameters.get("offset")).intValue();
            int limit = ((Number) parameters.get("pageSize")).intValue();
            List<Object> rows = new ArrayList<>();
            for (String code : scoped.stream().skip(offset).limit(limit).toList()) {
                var row = mock(ResultSet.class);
                when(row.getString("fund_code")).thenReturn(code);
                when(row.getString("fund_type")).thenReturn("INDEX");
                when(row.getTimestamp("created_at")).thenReturn(Timestamp.from(Instant.parse("2026-09-01T00:00:00Z")));
                rows.add(mapper.mapRow(row, rows.size()));
            }
            var query = mock(JdbcClient.MappedQuerySpec.class);
            when(query.list()).thenReturn(rows);
            return query;
        });
        when(ai.listFundSummariesByCodes(anyCollection())).thenAnswer(call -> {
            Collection<String> requested = call.getArgument(0);
            batches.add(List.copyOf(requested));
            assertTrue(requested.size() <= 50);
            assertTrue(codes.containsAll(requested));
            return requested.stream().map(code -> new AiFundSummary(code,
                    Integer.parseInt(code) % 2 == 0 ? "沪深ETF指数C" : "其他指数",
                    "INDEX", "ACTIVE", null, null, null, null)).toList();
        });
        when(credit.getQuota(user)).thenReturn(new WatchlistQuotaResponse(5, 52, 47, 47, 0, 52));
        service = new JdbcWatchlistService(jdbc, ai, credit);
    }

    private List<String> scopedCodes() {
        assertEquals(user.userId(), parameters.get("userId"));
        return parameters.get("fundType") == null || "INDEX".equals(parameters.get("fundType")) ? codes : List.of();
    }

    @AfterEach
    void clearContext() {
        CurrentUserContext.clear();
    }

    @Test
    void searchesNameAcrossBatchesBeforePagingAndKeepsFullQuota() {
        var result = service.listCurrentUserItems("  etf  ", "INDEX", 3, 10);
        assertEquals(26, result.totalCount());
        assertEquals(3, result.totalPages());
        assertEquals(List.of("000042", "000044", "000046", "000048", "000050", "000052"),
                result.items().stream().map(item -> item.fundCode()).toList());
        assertEquals(52, result.quota().activeWatchlistCount());
        assertEquals(List.of(50, 2), batches.stream().map(List::size).toList());
    }

    @Test
    void searchesPartialCodeBeyondFirstBatch() {
        var result = service.listCurrentUserItems("0052", null, 1, 10);
        assertEquals(1, result.totalCount());
        assertEquals("000052", result.items().get(0).fundCode());
    }

    @Test
    void supportsChineseNamesAndEmptyResults() {
        assertEquals(26, service.listCurrentUserItems("沪深", null, 1, 10).totalCount());
        var empty = service.listCurrentUserItems("未匹配", null, 1, 10);
        assertEquals(0, empty.totalCount());
        assertEquals(0, empty.totalPages());
        assertTrue(empty.items().isEmpty());
    }

    @Test
    void combinesTypeAndKeywordWithinCurrentUserScope() {
        var result = service.listCurrentUserItems("ETF", "BOND", 1, 10);
        assertEquals(0, result.totalCount());
        verifyNoInteractions(ai);
    }

    @Test
    void blankKeywordRestoresOriginalPageWithoutScanningAllRecords() {
        var result = service.listCurrentUserItems("  ", null, 2, 10);
        assertEquals(52, result.totalCount());
        assertEquals("000011", result.items().get(0).fundCode());
        assertEquals(List.of(10), batches.stream().map(List::size).toList());
    }

    @Test
    void searchFailureDoesNotReturnPartialTotalsButUnfilteredListStillDegrades() {
        doThrow(new AiServiceUnavailableException("synthetic unavailable", null))
                .when(ai).listFundSummariesByCodes(anyCollection());
        assertThrows(AiServiceUnavailableException.class,
                () -> service.listCurrentUserItems("ETF", null, 1, 10));
        var unfiltered = service.listCurrentUserItems(null, null, 1, 10);
        assertTrue(unfiltered.marketDataUnavailable());
        assertEquals(52, unfiltered.totalCount());
        assertEquals(10, unfiltered.items().size());
    }
}
