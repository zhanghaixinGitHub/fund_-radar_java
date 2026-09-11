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
        new AdviceScheduler(mock(DataSource.class),positions,market,predictions,service,repo,outcomes,clock).run();
        verify(predictions,times(1)).readExperiment("006730");
        verify(service).archive(eq(one),eq(position),eq(input),any(),eq(clock.instant()));
        verify(service).archive(eq(two),eq(position),eq(input),any(),eq(clock.instant()));
        verify(repo).review(pending,result);
        verify(positions).job(eq("portfolio-advice"),eq("SUCCEEDED"),anyString(),eq(clock.instant()),eq(true));
    }
}
