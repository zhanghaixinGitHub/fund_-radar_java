package com.fundradar.core.prediction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fundradar.core.auth.AccountRole;
import com.fundradar.core.auth.AuthenticatedUser;
import com.fundradar.core.auth.CurrentUserContext;
import com.fundradar.core.auth.PermissionCode;
import com.fundradar.core.auth.service.AuthenticationRequiredException;
import com.fundradar.core.direction1d.Direction1dRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 第二版仍核对本人关注；事后意见不会因读取而成为预测准确率样本。 */
class PredictionReviewV2Tests {
    private final ObjectMapper json=new ObjectMapper();
    private final MultiPredictionClient client=mock(MultiPredictionClient.class);
    private final Direction1dRepository scope=mock(Direction1dRepository.class);
    private final JdbcClient database=mock(JdbcClient.class);
    private final MultiPredictionService service=new MultiPredictionService(client,scope,database,json);
    private final UUID user=UUID.randomUUID();
    @AfterEach void clear() {CurrentUserContext.clear();}
    private void login(boolean write) {
        var permissions=new HashSet<>(Set.of(PermissionCode.FUND_READ,PermissionCode.WATCHLIST_SELF_READ));
        if(write) permissions.add(PermissionCode.WATCHLIST_SELF_WRITE);
        CurrentUserContext.set(new AuthenticatedUser(user,"isolated","隔离检查",AccountRole.FUND_USER,permissions));
    }
    private com.fasterxml.jackson.databind.JsonNode view() throws Exception {
        return json.readTree("""
          {"fundCode":"013330","periods":[
           {"horizonId":"T1","kind":"RETROSPECTIVE","decision":"DOWN","confidence":"LOW","excludedFromAccuracy":true},
           {"horizonId":"T5_V1","kind":"DATED_ANALYSIS","decision":"UP","confidence":"LOW"},
           {"horizonId":"T20_V1","kind":"DATED_ANALYSIS","decision":"UP","confidence":"LOW"},
           {"horizonId":"M6_V1","kind":"CONDITIONAL_OUTLOOK","decision":"UP","confidence":"LOW","excludedFromAccuracy":true} ]}
          """);
    }
    @Test void authorizationHappensBeforeRead() {
        assertThrows(AuthenticationRequiredException.class,()->service.reviewV2("013330"));
        login(false);
        assertThrows(NoSuchElementException.class,()->service.reviewV2("013330"));
        verifyNoInteractions(client,database);
    }
    @Test void readingRetainsKindAndDoesNotWriteForecastLinks() throws Exception {
        login(false);when(scope.follows(user,"013330")).thenReturn(true);
        var expected=view();when(client.get("/funds/013330/review-v2")).thenReturn(expected);
        assertEquals(expected,service.reviewV2("013330"));verifyNoInteractions(database);
    }
    @Test void absentAndWrongFundAndDuplicatePeriodStayDistinct() throws Exception {
        login(false);when(scope.follows(user,"013330")).thenReturn(true);
        when(client.get("/funds/013330/review-v2")).thenReturn(json.nullNode());
        assertNull(service.reviewV2("013330"));
        var wrong=view();((com.fasterxml.jackson.databind.node.ObjectNode)wrong).put("fundCode","021740");
        when(client.get("/funds/013330/review-v2")).thenReturn(wrong);
        assertThrows(IllegalStateException.class,()->service.reviewV2("013330"));
        var duplicate=view();((com.fasterxml.jackson.databind.node.ObjectNode)duplicate.path("periods").get(2)).put("horizonId","T5_V1");
        when(client.get("/funds/013330/review-v2")).thenReturn(duplicate);
        assertThrows(IllegalStateException.class,()->service.reviewV2("013330"));
    }
    @Test void retrospectiveCannotEnterAccuracyAndRefreshNeedsWritePermission() throws Exception {
        login(false);when(scope.follows(user,"013330")).thenReturn(true);
        var invalid=view();((com.fasterxml.jackson.databind.node.ObjectNode)invalid.path("periods").get(0)).put("excludedFromAccuracy",false);
        when(client.get("/funds/013330/review-v2")).thenReturn(invalid);
        assertThrows(IllegalStateException.class,()->service.reviewV2("013330"));
        assertThrows(RuntimeException.class,()->service.refreshReviewV2("013330"));
        verify(client,never()).post(anyString(),any());
        login(true);
        var waiting=json.readTree("{\"status\":\"WAITING_FOR_NAV\",\"summary\":\"等待新基准净值，当前保留上次分析。\"}");
        when(client.post("/funds/013330/review-v2/generate",Map.of())).thenReturn(waiting);
        assertEquals(waiting,service.refreshReviewV2("013330"));verifyNoInteractions(database);
    }
}
