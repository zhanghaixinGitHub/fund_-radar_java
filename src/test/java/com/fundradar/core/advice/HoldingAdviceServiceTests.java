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
    @Test void changedPersonalStateStopsBeforeAnyReportInsert() throws Exception {
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
        var second=first.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)second.path("position")).put("shares","99");
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
}
