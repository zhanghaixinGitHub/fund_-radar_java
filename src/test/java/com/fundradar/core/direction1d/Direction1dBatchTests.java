package com.fundradar.core.direction1d;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fundradar.core.auth.*;
import com.fundradar.core.auth.service.AccessDeniedException;
import com.fundradar.core.integration.ai.AiServiceProperties;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 合成回执验证跨账号去重、目标日冻结和服务鉴权；不代表真实预测正确率。 */
class Direction1dBatchTests {
    final ObjectMapper json=new ObjectMapper();
    final Direction1dRepository repo=mock(Direction1dRepository.class);
    final Direction1dService service=mock(Direction1dService.class);
    final Direction1dClient client=mock(Direction1dClient.class);
    final Direction1dBatchService batch=new Direction1dBatchService(repo,service,client);
    final LocalDate target=LocalDate.of(2026,9,23);
    @AfterEach void clear(){CurrentUserContext.clear();}

    @Test void oneFundFollowedByTwoUsersCountsOneCreationAndLinksBoth() throws Exception {
        var state=json.readTree("{\"window\":{\"target_nav_date\":\"2026-09-23\",\"status\":\"OPEN\"}}");
        when(service.checkedStatus()).thenReturn(state);
        var coverage=json.readTree("{\"items\":[{\"fund_code\":\"008888\",\"group_id\":\"CN_EQUITY\"}]}");
        when(client.coverage(List.of("008888"))).thenReturn(coverage);
        UUID first=UUID.randomUUID(),second=UUID.randomUUID(),scope=UUID.randomUUID();
        when(repo.predictionUsers("008888",null)).thenReturn(List.of(first,second));
        when(repo.predictionUsers("008888",second)).thenReturn(List.of());
        when(repo.scope(any(),eq(target),anyList())).thenReturn(scope);
        when(service.process(eq(first),eq(scope),any(),any())).thenReturn(Map.of("status","PREDICTED","reused",false));
        when(service.process(eq(second),eq(scope),any(),any())).thenReturn(Map.of("status","PREDICTED","reused",true));
        assertEquals(Map.of("status","PREDICTED","reused",false),batch.generate("008888",target));
        verify(service,times(2)).process(any(),eq(scope),any(),any());
        when(service.process(eq(first),eq(scope),any(),any())).thenReturn(Map.of("status","PREDICTED","reused",true));
        assertEquals(Map.of("status","PREDICTED","reused",true),batch.generate("008888",target));
    }

    @Test void neverSwitchesTargetDateHalfwayThroughBatch() throws Exception {
        when(service.checkedStatus()).thenReturn(json.readTree("{\"window\":{\"target_nav_date\":\"2026-09-24\"}}"));
        assertEquals("WINDOW_CHANGED",batch.generate("008888",target).get("status"));
        verifyNoInteractions(repo,client);
    }

    @Test void internalCallbackRejectsMissingTokenBrowserAndInjectedUser() {
        var properties=new AiServiceProperties();properties.setToken("prediction-test-token");
        var stub=mock(Direction1dBatchService.class);
        var controller=new InternalDirection1dSyncController(stub,properties);
        var request=new MockHttpServletRequest();
        assertThrows(AccessDeniedException.class,()->controller.fundCodes(request,""));
        request.addHeader("X-Service-Token","wrong");
        assertThrows(AccessDeniedException.class,()->controller.fundCodes(request,""));
        request.removeHeader("X-Service-Token");request.addHeader("X-Service-Token","prediction-test-token");
        request.addHeader("Origin","http://localhost");
        assertThrows(AccessDeniedException.class,()->controller.fundCodes(request,""));
        request.removeHeader("Origin");
        assertThrows(IllegalArgumentException.class,()->controller.generate(request,"008888",Map.of("targetNavDate",target.toString(),"userId","bad")));
        verifyNoInteractions(stub);
        when(stub.fundCodes("")).thenReturn(List.of("008888"));
        assertEquals(List.of("008888"),controller.fundCodes(request,""));
    }

    @Test void manualPredictionNeedsFollowAndTimeButNoSubscription() throws Exception {
        var actual=new Direction1dService(repo,client);
        UUID user=UUID.randomUUID();
        CurrentUserContext.set(new AuthenticatedUser(user,"test","测试",AccountRole.FUND_USER,
                Set.of(PermissionCode.FUND_READ,PermissionCode.WATCHLIST_SELF_READ,PermissionCode.WATCHLIST_SELF_WRITE)));
        when(repo.follows(user,"008888")).thenReturn(true);
        when(repo.now()).thenReturn(Instant.now());
        var state=json.createObjectNode().put("server_time",Instant.now().toString()).put("database_time",Instant.now().toString());
        state.putObject("window").put("target_nav_date","2026-09-23").put("status","OPEN");
        when(client.get("/status")).thenReturn(state);
        when(client.coverage(List.of("008888"))).thenReturn(json.readTree("{\"items\":[{\"fund_code\":\"008888\"}]}"));
        UUID forecast=UUID.randomUUID();when(repo.currentPublic("008888",target)).thenReturn(forecast);
        assertEquals("PREDICTED",actual.generate("008888").get("status"));
        verify(repo).confirm(forecast);
        ((com.fasterxml.jackson.databind.node.ObjectNode)state.path("window")).put("status","MISSED_DEADLINE");
        assertEquals("MISSED_DEADLINE",assertThrows(IllegalArgumentException.class,()->actual.generate("008888")).getMessage());
    }

    @Test void concurrentArchiveReuseIsNotReportedAsNewPrediction() throws Exception {
        var actual=new Direction1dService(repo,client);
        UUID user=UUID.randomUUID(),scope=UUID.randomUUID(),job=UUID.randomUUID(),forecast=UUID.randomUUID();
        var coverage=json.readTree("{\"fund_code\":\"008888\",\"group_id\":\"CN_EQUITY\",\"reason_codes\":[]}");
        var window=json.readTree("{\"target_nav_date\":\"2026-09-23\",\"status\":\"OPEN\"}");
        when(client.post("/forecast-jobs",Map.of("fund_code","008888"))).thenReturn(json.createObjectNode().put("job_id",job.toString()));
        when(client.get("/forecast-jobs/"+job)).thenReturn(json.readTree("{\"state\":\"SUCCEEDED\",\"result\":{\"payload_json\":\"raw\",\"content_hash\":\"hash\"}}"));
        // 初次查无档案，但持锁事务发现另一任务已完成相同目标日的保存。
        when(repo.archive(job,"raw","hash","008888")).thenReturn(new Direction1dRepository.ArchivedForecast(forecast,false));
        var result=actual.process(user,scope,coverage,window);
        assertEquals(true,result.get("reused"));
        verify(repo).link(user,forecast,scope);
    }
}
