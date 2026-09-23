package com.fundradar.core.advice;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.fundradar.core.advice.DecisionPolicyV2.*;
import static com.fundradar.core.advice.StrategyReplayEngine.*;

class StrategyReplayEngineTests {
    final DecisionPolicyV2 policy=new DecisionPolicyV2(new ObjectMapper());
    final StrategyReplayEngine engine=new StrategyReplayEngine(policy);
    Frame frame(int day,boolean up,boolean paused,boolean dividend) {
        var signal=new Signal("p"+day,"T20_V1",up?"UP":"NON_UP","m","h",1,"2024-01-01");
        return new Frame(LocalDate.of(2024,1,day),BigDecimal.ONE,dividend?new BigDecimal("0.1"):BigDecimal.ZERO,paused,
                new Input(List.of(signal),up?1.0:-1.0,List.of(),List.of(),false,"BALANCED",true,null,0.0,null,List.of()));
    }
    @Test void ledgerKeepsPendingCashAndCanReenter() {
        var frames=List.of(frame(2,true,false,false),frame(3,false,false,false),frame(4,true,false,false),
                frame(5,true,false,false),frame(8,false,false,false));
        var result=engine.run(frames,defaultConfig(),"V2");
        assertEquals("SELL",result.curve().get(1).action());
        assertTrue(result.curve().get(1).receivable().signum()>0);
        assertTrue(result.curve().get(2).receivable().signum()>0);
        assertEquals(0,result.curve().get(3).receivable().signum());
        assertTrue(result.trades().stream().filter(t->t.action().equals("BUY")).count()>=2);
        assertTrue(result.fees().signum()>0);
        assertEquals(LocalDate.of(2024,1,4),result.exitReviews().get(0).boughtBackOn());
        assertEquals(0,result.exitReviews().get(0).underlyingTotalReturn(),1e-12);
        assertTrue(result.exitReviews().get(0).exitFee().signum()>0);
        for(var point:result.curve()) assertEquals(0,point.equity().compareTo(point.availableCash().add(point.receivable()).add(point.shares())));
    }
    @Test void pausedRedemptionAndDividendsDoNotCreateNegativeCash() {
        var result=engine.run(List.of(frame(2,true,false,false),frame(3,false,true,true),frame(4,false,false,false)),defaultConfig(),"V2");
        assertFalse(result.trades().stream().anyMatch(t->t.date().equals(LocalDate.of(2024,1,3))));
        assertTrue(result.executionNotes().stream().anyMatch(t->t.contains("暂停赎回")));
    }
    @Test void onlineAndReplayCallSamePolicyAndHash() {
        Frame frame=frame(2,true,false,false);
        var result=engine.run(List.of(frame),defaultConfig(),"V2");
        var expected=policy.decide(frame.input());
        assertEquals(expected.decision(),result.curve().get(0).action());
        assertEquals(com.fundradar.core.direction1d.Direction1dPolicy.hash(expected.toString()),result.curve().get(0).decisionHash());
    }
    @Test void issuedAdviceUsesOriginalActionsAndHashesAcrossVersions() {
        var frames=List.of(frame(2,false,false,false),frame(3,true,false,false),frame(4,true,false,false),frame(5,true,false,true),frame(8,true,false,false));
        var issued=Map.of(LocalDate.of(2024,1,2),new IssuedDecision("BUY","release-old-report"),
                LocalDate.of(2024,1,3),new IssuedDecision("SELL","release-new-report"),
                LocalDate.of(2024,1,5),new IssuedDecision("BUY","release-new-next-report"));
        var result=engine.run(frames,defaultConfig(),"ISSUED_ADVICE",issued);
        assertEquals("BUY",result.curve().get(0).action()); // 当天重新计算会卖出，仍执行原建议。
        assertEquals("release-old-report",result.curve().get(0).decisionHash());
        assertEquals("SELL",result.curve().get(1).action());
        assertEquals("NO_REPORT",result.curve().get(2).action());
        assertEquals("NO_REPORT",result.curve().get(2).decisionHash());
        assertTrue(result.curve().get(2).receivable().signum()>0);
        assertEquals(0,result.curve().get(3).receivable().signum());
        assertTrue(result.trades().stream().filter(t->"BUY".equals(t.action())).count()>=2);
        assertTrue(result.executionNotes().stream().anyMatch(n->n.contains("缺少当时有效建议")));
    }
}
