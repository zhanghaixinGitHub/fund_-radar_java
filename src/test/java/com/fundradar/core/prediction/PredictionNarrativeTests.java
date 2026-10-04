package com.fundradar.core.prediction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fundradar.core.auth.*;
import com.fundradar.core.auth.service.AuthenticationRequiredException;
import com.fundradar.core.direction1d.*;
import com.fundradar.core.watchlist.service.WatchlistRequiredException;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 外部文案调用前必须通过本人关注/原档案授权，返回结果必须仍绑定当前预测。 */
class PredictionNarrativeTests {
    final Direction1dRepository scope=mock(Direction1dRepository.class);
    final Direction1dClient dailyClient=mock(Direction1dClient.class);
    final MultiPredictionClient multiClient=mock(MultiPredictionClient.class);
    final ObjectMapper json=new ObjectMapper();
    final Direction1dService daily=new Direction1dService(scope,dailyClient);
    final MultiPredictionService multi=new MultiPredictionService(multiClient,scope,mock(JdbcClient.class),json);
    final UUID user=UUID.randomUUID(),id=UUID.randomUUID(),job=UUID.randomUUID();
    @BeforeEach void login(){CurrentUserContext.set(new AuthenticatedUser(user,"test","测试",AccountRole.FUND_USER,
            Set.of(PermissionCode.FUND_READ,PermissionCode.WATCHLIST_SELF_READ)));}
    @AfterEach void clear(){CurrentUserContext.clear();}
    @Test void noLoginOrFollowCannotSpendExternalCalls(){
        CurrentUserContext.clear();
        assertThrows(AuthenticationRequiredException.class,()->daily.narrative("002112",id));
        assertThrows(AuthenticationRequiredException.class,()->multi.narrative("002112",id));
        login();
        assertThrows(WatchlistRequiredException.class,()->daily.narrative("002112",id));
        assertThrows(NoSuchElementException.class,()->multi.narrative("002112",id));
        verifyNoInteractions(dailyClient,multiClient);
    }
    @Test void anotherDailyArchiveCannotReachProvider(){
        when(scope.follows(user,"002112")).thenReturn(true);
        when(scope.evidenceSource(user,"002112",id)).thenThrow(new NoSuchElementException());
        assertThrows(NoSuchElementException.class,()->daily.narrative("002112",id));
        verifyNoInteractions(dailyClient);
    }
    @Test void dailyChecksOriginalHashAndMapsPublicId() throws Exception {
        when(scope.follows(user,"002112")).thenReturn(true);
        when(scope.evidenceSource(user,"002112",id)).thenReturn(Map.of("source_job_id",job,"content_hash","original"));
        String path="/forecast-jobs/"+job+"/narrative?fund_code=002112";
        var response=json.readTree("{\"state\":\"READY\",\"fundCode\":\"002112\",\"sourceId\":\""+job+"\",\"contentHash\":\"original\"}");
        when(dailyClient.post(path,Map.of())).thenReturn(response);
        var value=daily.narrative("002112",id);
        assertEquals(id.toString(),value.path("recordId").asText());
        assertEquals("daily",value.path("kind").asText());
        assertFalse(value.has("sourceId"));
        ((com.fasterxml.jackson.databind.node.ObjectNode)response).put("contentHash","other");
        assertThrows(IllegalStateException.class,()->daily.narrative("002112",id));
    }
    @Test void multiRejectsWrongFundAndRecord() throws Exception {
        when(scope.follows(user,"002112")).thenReturn(true);
        String path="/funds/002112/predictions/"+id+"/narrative";
        var response=json.readTree("{\"state\":\"FALLBACK\",\"fundCode\":\"002112\",\"sourceId\":\""+id+"\"}");
        when(multiClient.post(path,Map.of())).thenReturn(response);
        assertEquals(id.toString(),multi.narrative("002112",id).path("recordId").asText());
        ((com.fasterxml.jackson.databind.node.ObjectNode)response).put("sourceId",job.toString());
        assertThrows(IllegalStateException.class,()->multi.narrative("002112",id));
        ((com.fasterxml.jackson.databind.node.ObjectNode)response).put("sourceId",id.toString()).put("fundCode","000001");
        assertThrows(IllegalStateException.class,()->multi.narrative("002112",id));
    }
    @Test void controllersRejectClientFactsAndPrompts(){
        var controller=new MultiPredictionController(multi);
        assertThrows(IllegalArgumentException.class,()->controller.narrative("002112",id,Map.of("prompt","自定义")));
        var dailyController=new Direction1dController(daily,mock(Direction1dStatistics.class));
        assertThrows(IllegalArgumentException.class,()->dailyController.narrative("002112",id,Map.of("facts",List.of())));
        verifyNoInteractions(dailyClient,multiClient);
    }
}
