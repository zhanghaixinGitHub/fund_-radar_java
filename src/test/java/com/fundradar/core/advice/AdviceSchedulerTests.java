package com.fundradar.core.advice;

import com.fundradar.core.integration.ai.AiPredictionClient;
import com.fundradar.core.simulation.*;
import com.fundradar.core.watchlist.api.DirectionExperimentResponse;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 后台关闭网页仍执行，且清仓报告的核验不依赖活跃持仓；推理按基金复用。 */
class AdviceSchedulerTests {
    @Test void shares_public_inference_across_users_and_reviews_cleared_history() {
        var positions=mock(SimulationRepository.class); var market=mock(SimulationMarketClient.class);
        var predictions=mock(AiPredictionClient.class); var service=mock(AdviceService.class);
        var repo=mock(AdviceRepository.class); var outcomes=mock(AdviceOutcomeClient.class);
        var diagnoses=mock(DiagnosisClient.class); var diagnosisService=mock(DiagnosisService.class);
        var draftStats=mock(DraftStatsClient.class); var ruleService=mock(RuleService.class);
        var clock=Clock.fixed(Instant.parse("2026-09-11T02:00:00Z"),ZoneOffset.UTC);
        UUID one=UUID.randomUUID(),two=UUID.randomUUID();
        when(positions.workerUsers(null,50)).thenReturn(List.of(one,two));
        when(positions.workerUsers(two,50)).thenReturn(List.of());
        var z=BigDecimal.ZERO; var o=BigDecimal.ONE;
        var position=new SimulationTypes.Position("006730","测试",o,z,o,o,o,z,z,z,z,z,z,z,z,o,z,o,LocalDate.of(2026,9,10),null);
        when(positions.positions(one)).thenReturn(List.of(position)); when(positions.positions(two)).thenReturn(List.of(position));
        when(market.calendar()).thenReturn(new SimulationTypes.CalendarData("test",LocalDate.of(2026,1,1),LocalDate.of(2026,12,31),List.of(LocalDate.of(2026,9,11)),"test"));
        var input=DirectionExperimentResponse.unavailable("006730"); when(predictions.readExperiment("006730")).thenReturn(input);
        var pending=new AdviceTypes.Pending(UUID.randomUUID(),"001632","HOLD",LocalDate.of(2026,7,1),LocalDate.of(2026,7,29));
        when(repo.pending(LocalDate.of(2026,9,11),null,50)).thenReturn(List.of(pending));
        when(repo.pending(LocalDate.of(2026,9,11),pending.reportId(),50)).thenReturn(List.of());
        var result=new AdviceTypes.Outcome("001632",pending.start(),pending.end(),"DATA_INSUFFICIENT",clock.instant(),null,"等待资料","CASH_REINVESTMENT_20D_V1","TUSHARE_PRO_FUND",null,null);
        when(outcomes.read(pending)).thenReturn(result);
        var facts=new DiagnosisTypes.Facts("006730",LocalDate.of(2026,9,10),"VALID","HOLDING_DIAGNOSIS_FACTS_V1",List.of());
        when(diagnoses.read("006730",LocalDate.of(2026,9,11))).thenReturn(facts);
        var stats=new RuleTypes.DraftStats("006730","AVAILABLE",null,LocalDate.of(2026,9,10),600,540,"ACCUMULATED",Map.of("p50","0.05"),List.of(),"假设","HOLDING_RULE_DRAFT_STATS_V1");
        when(draftStats.read("006730")).thenReturn(stats);
        new AdviceScheduler(mock(DataSource.class),positions,market,predictions,service,repo,outcomes,diagnoses,diagnosisService,draftStats,ruleService,clock).run();
        verify(diagnoses,times(1)).read("006730",LocalDate.of(2026,9,11));
        verify(diagnosisService).archive(eq(one),eq(position),eq(facts),eq(clock.instant()));
        verify(diagnosisService).archive(eq(two),eq(position),eq(facts),eq(clock.instant()));
        // 草案统计按基金跨用户复用，同批次内每基金只读取一次。
        verify(draftStats,times(1)).read("006730");
        verify(ruleService).refreshDraft(eq(one),eq("006730"),eq(stats),eq(clock.instant()));
        verify(ruleService).refreshDraft(eq(two),eq("006730"),eq(stats),eq(clock.instant()));
        verify(predictions,times(1)).readExperiment("006730");
        verify(service).archive(eq(one),eq(position),eq(input),any(),eq(clock.instant()));
        verify(service).archive(eq(two),eq(position),eq(input),any(),eq(clock.instant()));
        verify(repo).review(pending,result);
        verify(positions).job(eq("portfolio-advice"),eq("SUCCEEDED"),anyString(),eq(clock.instant()),eq(true));
    }
    @Test void single_fund_failure_marks_partial_and_keeps_other_reports() {
        var positions=mock(SimulationRepository.class); var market=mock(SimulationMarketClient.class);
        var predictions=mock(AiPredictionClient.class); var service=mock(AdviceService.class);
        var repo=mock(AdviceRepository.class); var outcomes=mock(AdviceOutcomeClient.class);
        var diagnoses=mock(DiagnosisClient.class); var diagnosisService=mock(DiagnosisService.class);
        var draftStats=mock(DraftStatsClient.class); var ruleService=mock(RuleService.class);
        var clock=Clock.fixed(Instant.parse("2026-09-11T02:00:00Z"),ZoneOffset.UTC);
        UUID user=UUID.randomUUID();
        when(positions.workerUsers(null,50)).thenReturn(List.of(user));
        when(positions.workerUsers(user,50)).thenReturn(List.of());
        var z=BigDecimal.ZERO; var o=BigDecimal.ONE;
        var broken=new SimulationTypes.Position("001632","失败基金",o,z,o,o,o,z,z,z,z,z,z,z,z,o,z,o,LocalDate.of(2026,9,10),null);
        var healthy=new SimulationTypes.Position("006730","正常基金",o,z,o,o,o,z,z,z,z,z,z,z,z,o,z,o,LocalDate.of(2026,9,10),null);
        when(positions.positions(user)).thenReturn(List.of(broken,healthy));
        when(market.calendar()).thenReturn(new SimulationTypes.CalendarData("test",LocalDate.of(2026,1,1),LocalDate.of(2026,12,31),List.of(LocalDate.of(2026,9,11)),"test"));
        when(predictions.readExperiment(anyString())).thenReturn(DirectionExperimentResponse.unavailable("006730"));
        when(repo.pending(eq(LocalDate.of(2026,9,11)),any(),eq(50))).thenReturn(List.of());
        doThrow(new IllegalStateException("db down")).when(diagnosisService).archive(eq(user),eq(broken),any(),any());
        new AdviceScheduler(mock(DataSource.class),positions,market,predictions,service,repo,outcomes,diagnoses,diagnosisService,draftStats,ruleService,clock).run();
        // 来源失败不抛出：单基金Python故障记INSUFFICIENT归档，不拖垮整批；草案统计读取失败同理跳过。
        verify(diagnosisService).archive(eq(user),eq(broken),isNull(),any());
        verify(diagnosisService).archive(eq(user),eq(healthy),isNull(),any());
        verify(ruleService,never()).refreshDraft(any(),any(),any(),any());
        verify(service).archive(eq(user),eq(broken),any(),any(),any());
        verify(service).archive(eq(user),eq(healthy),any(),any(),any());
        verify(positions).job(eq("portfolio-advice"),eq("PARTIAL"),anyString(),eq(clock.instant()),eq(true));
    }
}
