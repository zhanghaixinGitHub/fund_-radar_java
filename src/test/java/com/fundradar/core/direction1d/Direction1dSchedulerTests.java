package com.fundradar.core.direction1d;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fundradar.core.integration.ai.MarketRevisionClient;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.*;

class Direction1dSchedulerTests {
    private final Direction1dRepository repo=mock(Direction1dRepository.class);
    private final Direction1dClient client=mock(Direction1dClient.class);
    private final MarketRevisionClient revisions=mock(MarketRevisionClient.class);
    private final Clock clock=mock(Clock.class);
    private final UUID id=UUID.randomUUID(),job=UUID.randomUUID();
    private final Map<String,Object> row=new HashMap<>(Map.of("forecast_id",id,"source_job_id",job,
            "fund_code","002112","base_nav_date",LocalDate.of(2026,10,8),
            "target_nav_date",LocalDate.of(2026,10,9),"has_outcome",true));

    private Direction1dScheduler scheduler(boolean training) {
        when(clock.instant()).thenReturn(Instant.parse("2026-10-11T04:00:00Z"));
        when(repo.reviewPage(null)).thenReturn(List.of(row));
        when(repo.reviewPage(id)).thenReturn(List.of());
        when(revisions.read(anyList())).thenReturn(new MarketRevisionClient.Snapshot("calendar",
                Map.of(id.toString(),new MarketRevisionClient.Revision("v2",true))));
        return new Direction1dScheduler(mock(DataSource.class),repo,client,revisions,clock,true,training);
    }
    private void answer() throws Exception {
        when(client.get("/labels/"+job+"?fetch_missing=false")).thenReturn(new ObjectMapper().readTree(
                "{\"payload\":{\"status\":\"AVAILABLE\",\"training_eligible\":true},\"content_hash\":\"label-v2\"}"));
        when(client.post(contains("/assessed"),any())).thenReturn(new ObjectMapper().readTree("{\"status\":\"ACKNOWLEDGED\"}"));
    }
    @Test void unchangedOrPendingDataNeverFetchesLabelsOrCreatesJobs() {
        var scheduler=scheduler(false); row.put("market_revision","v2"); scheduler.run();
        row.remove("market_revision");
        when(revisions.read(anyList())).thenReturn(new MarketRevisionClient.Snapshot("calendar",
                Map.of(id.toString(),new MarketRevisionClient.Revision("v2",false))));
        scheduler.run(); verifyNoInteractions(client); verify(repo,never()).reviewed(any(),anyString());
    }
    @Test void changedDataCommitsOutcomeThenAckThenCheckpoint() throws Exception {
        var scheduler=scheduler(false); answer(); scheduler.run();
        var order=inOrder(repo,client);
        order.verify(repo).outcome(eq(id),any());
        order.verify(client).post("/labels/"+job+"/assessed",Map.of("label_hash","label-v2"));
        order.verify(repo).reviewed(id,"v2");
        verify(client,never()).post(eq("/forecast-jobs"),any());
        verify(client,never()).coverage(anyList());
    }
    @Test void ackFailureLeavesCheckpointForRetry() throws Exception {
        var scheduler=scheduler(false); answer();
        when(client.post(contains("/assessed"),any())).thenThrow(new IllegalStateException("temporary"));
        scheduler.run(); scheduler.run();
        verify(repo,never()).reviewed(any(),anyString()); verify(repo,times(2)).outcome(eq(id),any());
    }
    @Test void firstFrozenAnswerGetsOneFollowUpForBaselineRevisions() throws Exception {
        var scheduler=scheduler(false); answer(); row.put("has_outcome",false); scheduler.run();
        verify(repo,never()).reviewed(any(),anyString());
        row.put("has_outcome",true); scheduler.run(); verify(repo).reviewed(id,"v2");
    }
    @Test void missingDataBetweenProbeAndReadDoesNotAdvanceCheckpoint() throws Exception {
        var scheduler=scheduler(false);
        when(client.get(anyString())).thenReturn(new ObjectMapper().readTree("{\"status\":\"PENDING_NAV\"}"));
        scheduler.run(); verify(repo,never()).reviewed(any(),anyString()); verify(client,never()).post(anyString(),any());
    }
    @Test void invalidLabelOrUnconfirmedAckCannotMarkVersionComplete() throws Exception {
        var scheduler=scheduler(false);
        when(client.get(anyString())).thenReturn(new ObjectMapper().readTree("{\"payload\":{\"status\":\"WAITING\"}}"));
        scheduler.run(); verify(repo,never()).outcome(any(),any());
        answer();
        when(client.post(contains("/assessed"),any())).thenReturn(new ObjectMapper().readTree("{}"));
        scheduler.run(); verify(repo,never()).reviewed(any(),anyString());
    }
    @Test void trainingCheckedOncePerWeekAndNeverBeforeSundayNoon() throws Exception {
        var scheduler=scheduler(true); row.put("market_revision","v2");
        when(clock.instant()).thenReturn(Instant.parse("2026-10-11T03:59:00Z")); scheduler.run();
        verifyNoInteractions(client);
        when(clock.instant()).thenReturn(Instant.parse("2026-10-11T04:00:00Z"));
        when(client.post("/training-jobs",Map.of())).thenReturn(new ObjectMapper().readTree("{\"state\":\"QUEUED\"}"));
        scheduler.run(); scheduler.run(); verify(client,times(1)).post("/training-jobs",Map.of());
        when(clock.instant()).thenReturn(Instant.parse("2026-10-18T04:00:00Z")); scheduler.run();
        verify(client,times(2)).post("/training-jobs",Map.of());
    }
}
