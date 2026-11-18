package com.fundradar.core.advice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fundradar.core.prediction.MultiPredictionClient;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class HoldingAdviceServiceTests {
    private final ObjectMapper json=new ObjectMapper();
    private final UUID owner=UUID.randomUUID();
    @SuppressWarnings("unchecked")
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"rating","prediction"})
    void holdingsListMarksChangedPublicInputsWithoutRewritingSavedAdvice(String changed) throws Exception {
        var db=mock(JdbcClient.class);var statement=mock(JdbcClient.StatementSpec.class,RETURNS_SELF);
        var query=mock(JdbcClient.MappedQuerySpec.class);when(db.sql(anyString())).thenReturn(statement);
        when(statement.query(String.class)).thenReturn(query);
        var personal=json.createObjectNode().put("preference","BALANCED");
        var saved=json.createObjectNode().put("fundCode","123456").put("strategyVersion",HoldingAdvicePolicy.VERSION)
                .put("validUntil","2026-11-18T00:00:00Z").put("summary","已保存原文");
        saved.set("personalSnapshot",personal);saved.set("ratingSnapshot",json.createObjectNode().put("score",70));
        saved.set("predictionSnapshot",json.createObjectNode().put("decision","UP"));
        when(query.list()).thenReturn(List.of(saved.toString()));
        var client=mock(MultiPredictionClient.class);
        when(client.rating("123456")).thenReturn(changed.equals("rating")?json.createObjectNode().put("score",71):saved.path("ratingSnapshot"));
        when(client.get("/funds/123456/review-v2")).thenReturn(changed.equals("prediction")?json.createObjectNode().put("decision","ABSTAIN"):saved.path("predictionSnapshot"));
        var legacy=mock(DecisionServiceV2.class);when(legacy.latestMine()).thenReturn(List.of());
        var service=new HoldingAdviceService(db,json,client,mock(DiagnosisClient.class),new HoldingAdvicePolicy(json),legacy,
                mock(TransactionTemplate.class),Clock.fixed(Instant.parse("2026-11-17T05:30:00Z"),ZoneOffset.UTC)) {
            @Override JsonNode personal(UUID user,String code) {return personal;}
        };
        com.fundradar.core.auth.CurrentUserContext.set(new com.fundradar.core.auth.AuthenticatedUser(owner,"test","测试",com.fundradar.core.auth.AccountRole.FUND_USER,Set.of(com.fundradar.core.auth.PermissionCode.PORTFOLIO_SELF_READ)));
        try {
            var view=service.latestMine().get(0);
            assertEquals("CHANGED",view.path("applicability").asText());
            assertEquals("已保存原文",view.path("summary").asText());assertFalse(saved.has("applicability"));
            verify(statement,never()).update();verify(client,never()).post(anyString(),any());
        } finally {com.fundradar.core.auth.CurrentUserContext.clear();}
    }
    @SuppressWarnings("unchecked")
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"shares","preference","funding"})
    void changedPersonalStateStopsBeforeAnyReportInsert(String changed) throws Exception {
        var db=mock(JdbcClient.class);var statement=mock(JdbcClient.StatementSpec.class,RETURNS_SELF);
        when(db.sql(anyString())).thenReturn(statement);
        var allowed=mock(JdbcClient.MappedQuerySpec.class);when(allowed.single()).thenReturn(true);
        when(statement.query(Boolean.class)).thenReturn(allowed);
        var account=mock(JdbcClient.MappedQuerySpec.class);when(account.single()).thenReturn(owner);
        when(statement.query(UUID.class)).thenReturn(account);
        when(statement.query(any(org.springframework.jdbc.core.RowMapper.class))).thenReturn(mock(JdbcClient.MappedQuerySpec.class));
        var tx=mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(call -> ((TransactionCallback<?>)call.getArgument(0)).doInTransaction(null));
        var client=mock(MultiPredictionClient.class);when(client.rating("123456")).thenReturn(json.nullNode());
        when(client.get("/funds/123456/review-v2")).thenReturn(json.nullNode());
        var diagnoses=mock(DiagnosisClient.class);when(diagnoses.read(anyString(),any())).thenThrow(new IllegalStateException("unavailable"));
        var first=json.readTree("""
            {"position":{"shares":"100","availableShares":"100","navDate":"2026-11-13"},"orders":[],"plans":[]}
            """);
        var second=first.deepCopy();
        if(changed.equals("shares")) ((com.fasterxml.jackson.databind.node.ObjectNode)second.path("position")).put("shares","99");
        else if(changed.equals("preference")) ((com.fasterxml.jackson.databind.node.ObjectNode)second).put("preference","LONG");
        else ((com.fasterxml.jackson.databind.node.ObjectNode)second).putObject("funding").put("status","REVOKED");
        var reads=new AtomicInteger();
        var service=new HoldingAdviceService(db,json,client,diagnoses,new HoldingAdvicePolicy(json),mock(DecisionServiceV2.class),tx,
                Clock.fixed(Instant.parse("2026-11-16T09:00:00Z"),ZoneOffset.UTC)) {
            @Override JsonNode personal(UUID user,String code) {return reads.getAndIncrement()==0?first:second;}
        };
        assertThrows(IllegalArgumentException.class,()->service.generateFor(owner,"123456"));
        verify(statement,never()).update();verify(client,never()).post(anyString(),any());
        verify(db).sql(contains("FOR UPDATE"));verify(db).sql(contains("721109"));
    }
    @SuppressWarnings("unchecked")
    @Test void inactiveOrUnauthorizedOwnerCannotReadPublicInputs() {
        var db=mock(JdbcClient.class);var statement=mock(JdbcClient.StatementSpec.class,RETURNS_SELF);
        when(db.sql(anyString())).thenReturn(statement);
        var allowed=mock(JdbcClient.MappedQuerySpec.class);when(allowed.single()).thenReturn(false);
        when(statement.query(Boolean.class)).thenReturn(allowed);
        var client=mock(MultiPredictionClient.class);
        var service=new HoldingAdviceService(db,json,client,mock(DiagnosisClient.class),new HoldingAdvicePolicy(json),
                mock(DecisionServiceV2.class),mock(TransactionTemplate.class),Clock.systemUTC());
        assertThrows(com.fundradar.core.auth.service.AccessDeniedException.class,()->service.generateFor(owner,"123456"));
        verifyNoInteractions(client);verify(statement,never()).update();
    }

    @SuppressWarnings("unchecked")
    @Test void previousReportsRemainReadableAsHistoryWithoutBeingRewritten() throws Exception {
        var db=mock(JdbcClient.class);var statement=mock(JdbcClient.StatementSpec.class,RETURNS_SELF);
        var query=mock(JdbcClient.MappedQuerySpec.class);when(db.sql(anyString())).thenReturn(statement);
        when(statement.query(String.class)).thenReturn(query);
        var old=json.createObjectNode().put("strategyVersion",HoldingAdvicePolicy.PREVIOUS_VERSION).put("summary","旧建议原文").put("fundCode","123456");
        when(query.optional()).thenReturn(Optional.of(old.toString()));when(query.list()).thenReturn(List.of(old.toString()));
        var client=mock(MultiPredictionClient.class);
        var service=new HoldingAdviceService(db,json,client,mock(DiagnosisClient.class),new HoldingAdvicePolicy(json),mock(DecisionServiceV2.class),mock(TransactionTemplate.class),Clock.systemUTC());
        com.fundradar.core.auth.CurrentUserContext.set(new com.fundradar.core.auth.AuthenticatedUser(owner,"test","测试",com.fundradar.core.auth.AccountRole.FUND_USER,Set.of(com.fundradar.core.auth.PermissionCode.PORTFOLIO_SELF_READ)));
        try {
            var latest=service.latest("123456");assertEquals("LEGACY",latest.path("applicability").asText());
            assertEquals("旧建议原文",latest.path("summary").asText());
            assertEquals(old,service.history("123456",1,HoldingAdvicePolicy.PREVIOUS_VERSION).get(0));
            verify(statement,never()).update();verifyNoInteractions(client);
        } finally {com.fundradar.core.auth.CurrentUserContext.clear();}
    }
}
