package com.fundradar.core.direction1d;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fundradar.core.auth.service.AccessDeniedException;
import com.fundradar.core.integration.ai.AiServiceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class Direction1dOutcomeTests {
    final Direction1dRepository repo=mock(Direction1dRepository.class);
    final Direction1dClient client=mock(Direction1dClient.class);
    final UUID owner=UUID.randomUUID(),id=UUID.randomUUID(),job=UUID.randomUUID();
    final LocalDate due=LocalDate.of(2026,11,11);
    final Direction1dOutcomeService service=new Direction1dOutcomeService(repo,client,
            Clock.fixed(Instant.parse("2026-11-10T16:30:00Z"),ZoneOffset.UTC));
    void row(boolean ready) {
        when(repo.dueOutcomes(owner,due,null)).thenReturn(List.of(Map.of("forecast_id",id,"source_job_id",job,
                "fund_code","006038","has_outcome",ready)));
        when(repo.holds(owner,"006038")).thenReturn(true);
        when(repo.predictionEligible(owner,"006038")).thenReturn(true);
    }
    @Test void appendsAndReadsBackWithoutGeneratingTrainingOrLearning() throws Exception {
        row(false);
        var label=new ObjectMapper().readTree("{\"payload\":{\"status\":\"AVAILABLE\"}}");
        when(client.get(anyString())).thenReturn(label);when(repo.hasVerifiedOutcome(id)).thenReturn(true);
        var result=service.reconcile(owner,due,null);
        assertEquals(1,result.get("completed"));assertEquals(1,result.get("created"));
        var ordered=inOrder(repo);ordered.verify(repo).dueOutcomes(owner,due,null);
        ordered.verify(repo).holds(owner,"006038");ordered.verify(repo).predictionEligible(owner,"006038");
        ordered.verify(repo).outcome(id,label);ordered.verify(repo).hasVerifiedOutcome(id);
        ordered.verify(repo).outcomeCheck(id,"SUCCEEDED");
        verify(client).get("/labels/"+job+"?fetch_missing=false");verify(client,never()).post(anyString(),any());
    }
    @Test void waitingAndValidationFailureAreDifferentAndRetryDoesNotCountOldResultAsNew() throws Exception {
        row(true);when(client.get(anyString())).thenReturn(new ObjectMapper().readTree("{\"status\":\"PENDING_NAV\"}"));
        assertEquals(1,service.reconcile(owner,due,null).get("pending"));
        verify(repo).outcomeCheck(id,"PENDING_DATA");verify(repo,never()).outcome(any(),any());
        when(client.get(anyString())).thenReturn(new ObjectMapper().readTree("{\"payload\":{\"status\":\"AVAILABLE\"}}"));
        doThrow(new IllegalArgumentException("ANALYSIS_LABEL_FUND")).when(repo).outcome(any(),any());
        assertEquals(1,service.reconcile(owner,due,null).get("failed"));verify(repo).outcomeCheck(id,"FAILED");
        doNothing().when(repo).outcome(any(),any());when(repo.hasVerifiedOutcome(id)).thenReturn(true);
        assertEquals(0,service.reconcile(owner,due,null).get("created"));
    }
    @Test void clearedHoldingCannotBeProcessedAndFutureCutoffRejected() {
        row(false);when(repo.holds(owner,"006038")).thenReturn(false);
        assertEquals(1,service.reconcile(owner,due,null).get("failed"));verifyNoInteractions(client);
        assertThrows(IllegalArgumentException.class,()->service.reconcile(owner,due.plusDays(1),null));
    }
    @Test void missingReadbackCannotClaimSuccess() throws Exception {
        row(false);when(client.get(anyString())).thenReturn(new ObjectMapper().readTree("{\"payload\":{\"status\":\"AVAILABLE\"}}"));
        assertEquals(1,service.reconcile(owner,due,null).get("failed"));
        verify(repo).outcomeCheck(id,"FAILED");
    }
    @Test void explicitArchiveBackfillUsesOnlyOwnerArchiveAndDoesNotChangeDailyHoldingsScope() throws Exception {
        when(repo.dueOutcomes(owner,due,null,true)).thenReturn(List.of(Map.of("forecast_id",id,"source_job_id",job,
                "fund_code","006038","has_outcome",false)));
        when(client.get(anyString())).thenReturn(new ObjectMapper().readTree("{\"payload\":{\"status\":\"AVAILABLE\"}}"));
        when(repo.hasVerifiedOutcome(id)).thenReturn(true);
        assertEquals(1,service.reconcile(owner,due,null,true).get("completed"));
        verify(repo,never()).dueOutcomes(owner,due,null);verify(repo,never()).holds(any(),anyString());
        verify(client,never()).post(anyString(),any());
    }
    @Test void analysisLabelMustMatchFundInBothNavSourcesEvenWithValidEnvelopeHash() throws Exception {
        var json=new ObjectMapper();
        var original=json.createObjectNode().put("fund_code","006038").put("task_key","original")
                .put("input_snapshot_id","input").put("target_nav_date","2026-11-05").put("base_nav_date","2026-11-04")
                .put("target_definition","UNIT_NAV_DIRECTION_THREE_STATE_V2").put("generated_at","2026-11-05T02:07:00Z");
        original.putObject("input").put("source_id","source");
        var payload=json.createObjectNode().put("fund_code","006038").put("task_key","original")
                .put("input_snapshot_id","input").put("target_nav_date","2026-11-05")
                .put("target_definition","UNIT_NAV_DIRECTION_THREE_STATE_V2").put("training_eligible",false)
                .put("label_observed_at","2026-11-11T00:30:00Z").put("base_unit_nav","1")
                .put("target_unit_nav","1.1").put("nav_return","0.100000000000").put("y",1).put("actual_direction","UP");
        for(String side:List.of("base_source","target_source")) payload.putObject(side)
                .put("fund_code","006038").put("source_id","source").put("version_id",UUID.randomUUID().toString())
                .put("nav_date",side.equals("base_source")?"2026-11-04":"2026-11-05")
                .put("unit_nav",side.equals("base_source")?"1":"1.1").put("content_hash","a".repeat(64))
                .put("observed_at","2026-11-11T00:30:00Z");
        for(String mismatch:List.of("none","fund_code","base_source","target_source")) {
            var p=payload.deepCopy();
            if(mismatch.equals("fund_code")) p.put("fund_code","002112");
            else if(!mismatch.equals("none")) ((com.fasterxml.jackson.databind.node.ObjectNode)p.path(mismatch)).put("fund_code","002112");
            var envelope=json.createObjectNode().put("snapshot_id",UUID.randomUUID().toString())
                    .put("payload_json",p.toString()).put("content_hash",Direction1dPolicy.hash(p.toString()));
            envelope.set("payload",p);
            if(mismatch.equals("none")) assertDoesNotThrow(()->Direction1dAnalysisPolicy.validateLabel(json,envelope,original,clockTime()));
            else assertThrows(IllegalArgumentException.class,()->Direction1dAnalysisPolicy.validateLabel(json,envelope,original,clockTime()));
        }
    }
    private Instant clockTime() { return Instant.parse("2026-11-11T01:00:00Z"); }
    @Test void internalEndpointRejectsBrowserWrongTokenAndUnexpectedBody() {
        var properties=new AiServiceProperties();properties.setToken("only-test");
        var controller=new InternalDirection1dOutcomeController(service,properties);
        var request=new MockHttpServletRequest();var body=Map.of("dueBefore",due.toString());
        assertThrows(AccessDeniedException.class,()->controller.reconcile(request,owner,body));
        request.addHeader("X-Service-Token","only-test");request.addHeader("Origin","http://local");
        assertThrows(AccessDeniedException.class,()->controller.reconcile(request,owner,body));
        request.removeHeader("Origin");
        assertThrows(IllegalArgumentException.class,()->controller.reconcile(request,owner,Map.of("fundCodes","006038")));
        verifyNoInteractions(repo,client);
    }
}
