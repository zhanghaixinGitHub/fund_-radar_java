package com.fundradar.core.advice;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static com.fundradar.core.advice.DecisionPolicyV2.*;
import static com.fundradar.core.advice.StrategyReplayEngine.*;
import static org.junit.jupiter.api.Assertions.*;

class StrategyReplayComparisonTests {
    final StrategyReplayEngine engine=new StrategyReplayEngine(new DecisionPolicyV2(new ObjectMapper()));
    final StrategyReplayComparison comparison=new StrategyReplayComparison(engine);
    Frame frame(int day,String nav,boolean paused) {
        return new Frame(LocalDate.of(2024,1,day),new BigDecimal(nav),BigDecimal.ZERO,paused,
                new Input(List.of(),null,List.of(),List.of(),false,null,false,null,null,null,List.of()));
    }
    @Test void fivePredeclaredControlsUseSameFeesAndKeepUninvestedCash() {
        var frames=List.of(frame(2,"1",false),frame(3,"1.1",false),frame(4,"0.9",false));
        Result held=engine.run(frames,defaultConfig(),"BUY_HOLD");
        var result=comparison.compare(held,frames,defaultConfig());
        assertEquals(5,result.controls().size());
        assertEquals(0,result.controls().get(0).result().tradeCount());
        assertEquals(0,result.controls().get(0).result().netReturn(),1e-12);
        assertEquals(held.finalEquity(),result.controls().get(4).result().finalEquity());
        assertEquals(held.fees(),result.controls().get(4).result().fees());
        assertEquals(new BigDecimal("5000.00"),result.controls().get(2).result().curve().get(0).availableCash());
        assertTrue(result.controls().get(2).execution().meanInvestedFraction()<0.6);
    }
    @Test void noReportPausedRedemptionAndUnarrivedCashRemainInAccounting() {
        var frames=List.of(frame(2,"1",false),frame(3,"1",true),frame(4,"1",false),frame(5,"1.2",false),frame(8,"1.3",false));
        var issued=Map.of(frames.get(0).date(),new IssuedDecision("BUY","one"),frames.get(1).date(),new IssuedDecision("SELL","two"),frames.get(2).date(),new IssuedDecision("SELL","three"));
        var result=comparison.compare(engine.run(frames,defaultConfig(),"ISSUED_ADVICE",issued),frames,defaultConfig());
        assertEquals(3,result.execution().requestedTradeDays());assertEquals(2,result.execution().executedTradeDays());
        assertEquals(1,result.execution().unexecutedTradeDays());assertEquals(2,result.execution().pendingCashSessions());
        assertEquals(2,result.execution().noReportSessions());assertTrue(result.strategy().exitReviews().get(0).missedRise()>0);
        assertNull(result.strategy().exitReviews().get(0).boughtBackOn());
    }
    @Test void mismatchedDatesOrOutOfRangeRatioRejected() {
        var frames=List.of(frame(2,"1",false));var result=engine.run(frames,defaultConfig(),"BUY_HOLD");
        assertThrows(IllegalArgumentException.class,()->comparison.compare(result,List.of(frame(3,"1",false)),defaultConfig()));
        assertThrows(IllegalArgumentException.class,()->engine.runInitialExposureControl(frames,defaultConfig(),new BigDecimal("1.1")));
    }
}
