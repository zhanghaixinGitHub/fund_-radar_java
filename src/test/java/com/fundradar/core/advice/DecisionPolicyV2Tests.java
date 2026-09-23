package com.fundradar.core.advice;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static com.fundradar.core.advice.DecisionPolicyV2.*;

class DecisionPolicyV2Tests {
    final DecisionPolicyV2 policy=new DecisionPolicyV2(new ObjectMapper());
    Signal signal(String horizon,String direction) { return new Signal("p",horizon,direction,"m","h",1,"2026-09-21"); }
    Input input(boolean held,String preference,List<Signal> signals,Double trend) {
        return new Input(signals,trend,List.of(new Fact("经理","任职变更未推断方向","TUSHARE",0)),List.of("新闻未接入"),
                held,preference,true,null,-.01,null,List.of());
    }
    @Test void smallHoldingStillGetsSellAndZeroHoldingCanBuy() {
        // 金额不作为组件输入，十元与十万元在同一公共快照下得到相同动作。
        var negative=input(true,"BALANCED",List.of(signal("T20_V1","NON_UP")),-1.0);
        assertEquals("SELL",policy.decide(negative).decision());
        assertEquals("BUY",policy.decide(input(false,"BALANCED",List.of(signal("T20_V1","UP")),1.0)).decision());
        assertEquals("AVOID",policy.decide(input(false,"BALANCED",List.of(signal("T20_V1","NON_UP")),-1.0)).decision());
    }
    @Test void preferenceChangesDecisionButNotPublicPredictions() {
        var signals=List.of(signal("T5_V1","NON_UP"),signal("T20_V1","UP"),signal("M6_V1","UP"));
        assertEquals("REDUCE",policy.decide(input(true,"SHORT",signals,0.0)).decision());
        assertEquals("HOLD",policy.decide(input(true,"LONG",signals,0.0)).decision());
        assertEquals(signals,policy.decide(input(true,"LONG",signals,0.0)).modelRefs());
    }
    @Test void missingPredictionIsFailureAndPersonalRuleWins() {
        assertNull(policy.decide(input(true,"BALANCED",List.of(),null)).decision());
        var basic=input(true,"BALANCED",List.of(signal("T20_V1","UP")),1.0);
        var constrained=new Input(basic.predictions(),basic.trendRisk(),basic.facts(),basic.missing(),true,"BALANCED",false,
                .15,-.01,new PersonalRule("r",10.0,5.0),List.of("仍需等待份额可卖"));
        var result=policy.decide(constrained);
        assertEquals("REDUCE",result.decision());
        assertTrue(result.opposingEvidence().stream().anyMatch(v->v.contains("个人规则")));
    }
    @Test void factsOnlyMoveScoreWhenTheirDefinedFactorChanges() {
        var original=input(true,"BALANCED",List.of(signal("T20_V1","UP")),0.0);
        var changedManager=new Input(original.predictions(),0.0,List.of(new Fact("经理","新任职公告","TUSHARE",0)),
                original.missing(),true,"BALANCED",true,null,null,null,List.of());
        assertEquals(policy.decide(original).score(),policy.decide(changedManager).score());
        assertNotEquals(policy.decide(original).facts(),policy.decide(changedManager).facts());
        var definedEvent=new Input(original.predictions(),0.0,List.of(new Fact("测试已登记规则","可复算负向样例","FIXTURE",-1)),
                original.missing(),true,"BALANCED",true,null,null,null,List.of());
        assertEquals(.2,policy.decide(original).score()-policy.decide(definedEvent).score(),1e-12);
        assertEquals(.2,policy.decide(input(true,"BALANCED",original.predictions(),1.0)).score()-policy.decide(original).score(),1e-12);
    }
}
