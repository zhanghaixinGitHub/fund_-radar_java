package com.fundradar.core.direction1d;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fundradar.core.auth.service.AccessDeniedException;
import com.fundradar.core.integration.ai.AiServiceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 原任务续查的隔离验证；不生成真实预测，不调用模型或真实账号。 */
class Direction1dReconciliationTests {
    final ObjectMapper json=new ObjectMapper();
    final Direction1dRepository repo=mock(Direction1dRepository.class);
    final Direction1dClient client=mock(Direction1dClient.class);
    final Direction1dService service=mock(Direction1dService.class);
    final Direction1dBatchService batch=new Direction1dBatchService(repo,service,client);
    final LocalDate target=LocalDate.of(2026,10,12);
    final UUID job=UUID.randomUUID(),scope=UUID.randomUUID(),user=UUID.randomUUID(),forecast=UUID.randomUUID();

    ObjectNode task(String state) {
        when(repo.syncJobScopes("002112",target,job,null)).thenReturn(List.of(Map.of("snapshot_id",scope,"user_id",user)));
        when(repo.syncJobScopes("002112",target,job,scope)).thenReturn(List.of());
        var result=json.createObjectNode().put("kind","FORECAST").put("state",state);
        result.putObject("payload").put("fund_code","002112").put("target_nav_date",target.toString());
        when(client.get("/forecast-jobs/"+job)).thenReturn(result);
        return result;
    }

    @Test void delayedFailureReplacesRunningWithoutResubmitting() {
        task("FAILED").putObject("result").put("reason","ANALYSIS_REVIEW_FAILED");
        assertEquals(Map.of("status","FAILED","reason","ANALYSIS_REVIEW_FAILED"),batch.reconcile("002112",target,job));
        verify(repo).finishSyncJob("002112",target,job,"FAILED","ANALYSIS_REVIEW_FAILED");
        verify(repo,never()).archive(any(),any(),any(),any());
        verify(client,never()).post(anyString(),any());verifyNoInteractions(service);
    }

    @Test void runningRemainsPendingAndCannotClaimArchive() {
        task("RUNNING");
        assertEquals("RUNNING",batch.reconcile("002112",target,job).get("status"));
        verify(repo,never()).finishSyncJob(any(),any(),any(),any(),any());
        verify(repo,never()).archive(any(),any(),any(),any());verifyNoInteractions(service);
    }

    void ready() throws Exception {
        String raw="{\"target_nav_date\":\"2026-10-12\"}";
        task("SUCCEEDED").putObject("result").put("payload_json",raw).put("content_hash","hash");
        when(repo.decode(raw)).thenReturn(json.readTree(raw));
        when(repo.archive(job,raw,"hash","002112")).thenReturn(new Direction1dRepository.ArchivedForecast(forecast,true));
    }

    @Test void successRequiresArchiveAndLinksOnlyOriginalScopes() throws Exception {
        ready();
        assertEquals(Map.of("status","PREDICTED","reused",false),batch.reconcile("002112",target,job));
        verify(repo).link(user,forecast,scope);
        verify(repo).finishSyncJob("002112",target,job,"PREDICTED",null);
        verify(repo,never()).scope(any(),any(),any());
        verify(client,never()).post(anyString(),any());verifyNoInteractions(service);
        when(repo.archive(any(),anyString(),anyString(),anyString())).thenReturn(new Direction1dRepository.ArchivedForecast(forecast,false));
        assertEquals(true,batch.reconcile("002112",target,job).get("reused"));
    }

    @Test void archiveDeadlineNeverBecomesSuccess() throws Exception {
        ready();
        when(repo.archive(any(),anyString(),anyString(),anyString())).thenThrow(new IllegalArgumentException("MISSED_DEADLINE"));
        assertEquals("ARCHIVE_DEADLINE_PASSED",batch.reconcile("002112",target,job).get("reason"));
        verify(repo,never()).link(any(),any(),any());
    }

    @Test void foreignFundOrTargetAndUnknownScopeAreRejected() {
        var foreign=task("SUCCEEDED");
        ((ObjectNode)foreign.path("payload")).put("fund_code","000001");
        assertThrows(IllegalArgumentException.class,()->batch.reconcile("002112",target,job));
        ((ObjectNode)foreign.path("payload")).put("fund_code","002112").put("target_nav_date","2026-10-13");
        assertThrows(IllegalArgumentException.class,()->batch.reconcile("002112",target,job));
        assertEquals("JOB_SCOPE_UNAVAILABLE",batch.reconcile("002112",target,UUID.randomUUID()).get("reason"));
        verify(repo,never()).archive(any(),any(),any(),any());verifyNoInteractions(service);
    }

    @Test void reconcileUsesSameInternalAuthorizationAndStrictBody() {
        var properties=new AiServiceProperties();properties.setToken("reconcile-test");
        var controller=new InternalDirection1dSyncController(batch,properties);
        var request=new MockHttpServletRequest();
        var body=Map.of("targetNavDate",target.toString());
        assertThrows(AccessDeniedException.class,()->controller.reconcile(request,"002112",job,body));
        request.addHeader("X-Service-Token","reconcile-test");request.addHeader("Origin","http://localhost");
        assertThrows(AccessDeniedException.class,()->controller.reconcile(request,"002112",job,body));
        request.removeHeader("Origin");
        assertThrows(IllegalArgumentException.class,()->controller.reconcile(request,"002112",job,
                Map.of("targetNavDate",target.toString(),"userId",user.toString())));
        verifyNoInteractions(repo,client,service);
    }
}
