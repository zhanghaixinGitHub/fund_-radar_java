package com.fundradar.core.advice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class HoldingAdvicePolicyTests {
    private final ObjectMapper json=new ObjectMapper();
    private final HoldingAdvicePolicy policy=new HoldingAdvicePolicy(json);
    private final Instant now=Instant.parse("2026-11-16T09:20:00Z");
    private ObjectNode personal() throws Exception {return (ObjectNode)json.readTree("""
        {"position":{"shares":"100","availableShares":"100","frozenShares":"0","holdingGainRate":"0.12","navDate":"2026-11-13"},"orders":[],"plans":[],"rule":null}
        """);}
    private ObjectNode rating() throws Exception {return (ObjectNode)json.readTree("""
        {"fundCode":"123456","status":"SCORED","score":"70","asOfDate":"2026-10-30","validUntil":"2026-12-01T00:00:00+08:00","dimensions":[{"name":"风险","explanation":"有波动风险"}],"limitations":[]}
        """);}
    private ObjectNode review(String shortDirection,String medium) {
        var root=json.createObjectNode();root.put("fundCode","123456");var periods=root.putArray("periods");
        for(String h:new String[]{"T5_V1","T20_V1"}) {
            var p=periods.addObject();p.put("horizonId",h);p.put("decision",h.equals("T5_V1")?shortDirection:medium);
            p.put("kind","DATED_ANALYSIS");p.put("startDate","2026-11-16");p.put("endDate","2026-11-25");p.put("baseNavDate","2026-11-13");
            p.put("sourceCutoff","2026-11-16T10:00:00+08:00");p.put("analysisAt","2026-11-16T11:00:00+08:00");
            p.put("primaryReason","同一经营事实");p.put("counterReason","相反的成本风险");p.putArray("conditions").add("经营订单变化后复核");
        }
        return root;
    }
    private ObjectNode decide(ObjectNode p,ObjectNode r,ObjectNode v) {return policy.decide("123456",p,r,v,json.nullNode(),now);}
    @Test void bothDownReduceWithoutSellingAll() throws Exception {
        var result=decide(personal(),rating(),review("DOWN","DOWN"));assertEquals("REDUCE",result.path("decision").asText());assertFalse(result.has("score"));
    }
    @Test void onlyOnePeriodCannotNormalizeMissingEvidence() throws Exception {
        var v=review("UP","UP");v.withArray("periods").remove(1);var result=decide(personal(),rating(),v);
        assertTrue(result.path("decision").isNull());assertEquals("INSUFFICIENT",result.path("adviceState").asText());
    }
    @Test void shortDownLongSupportDoesNotMeanExit() throws Exception {
        var result=decide(personal(),rating(),review("DOWN","UP"));assertEquals("HOLD",result.path("decision").asText());
    }
    @Test void highScoreDoesNotTriggerBuying() throws Exception {
        var r=rating();r.put("score","99");assertEquals("HOLD",decide(personal(),r,review("UP","UP")).path("decision").asText());
    }
    @Test void conditionalOutlookCannotReplaceMediumPeriod() throws Exception {
        var v=review("UP","UP");((ObjectNode)v.path("periods").get(1)).put("kind","CONDITIONAL_OUTLOOK");
        assertTrue(decide(personal(),rating(),v).path("decision").isNull());
    }
    @Test void partialStaleAndExpiredRatingCannotPretendComplete() throws Exception {
        for(String state:new String[]{"PARTIAL","STALE","WITHDRAWN"}) {var r=rating();r.put("status",state);assertTrue(decide(personal(),r,review("UP","UP")).path("decision").isNull());}
        var r=rating();r.put("validUntil","2026-11-15T00:00:00Z");assertTrue(decide(personal(),r,review("UP","UP")).path("decision").isNull());
    }
    @Test void retrospectiveAndFutureSourcesNeverDriveActions() throws Exception {
        for(String field:new String[]{"kind","sourceCutoff","endDate"}) {
            var v=review("DOWN","DOWN");var p=(ObjectNode)v.path("periods").get(0);
            p.put(field,field.equals("kind")?"RETROSPECTIVE":field.equals("endDate")?"2026-11-13":"2026-11-17T00:00:00Z");
            assertTrue(decide(personal(),rating(),v).path("decision").isNull());
        }
    }
    @Test void pendingSellAndFrozenSharesPreventRepeatedSelling() throws Exception {
        var p=personal();p.withArray("orders").addObject().put("side","SELL");
        assertEquals("HOLD",decide(p,rating(),review("DOWN","DOWN")).path("decision").asText());
        p=personal();((ObjectNode)p.path("position")).put("availableShares","0").put("frozenShares","100");
        assertEquals("HOLD",decide(p,rating(),review("DOWN","DOWN")).path("decision").asText());
    }
    @Test void confirmedRuleUsesDecimalRatioNotPercent() throws Exception {
        var p=personal();p.putObject("rule").put("takeProfitPct","0.10");
        assertEquals("REDUCE",decide(p,rating(),review("UP","UP")).path("decision").asText());
        ((ObjectNode)p.path("position")).put("holdingGainRate","0.0012");
        assertEquals("HOLD",decide(p,rating(),review("UP","UP")).path("decision").asText());
    }
    @Test void actualDrawdownUsesSameDateAndNegativeRatio() throws Exception {
        var p=personal();p.putObject("rule").put("reduceDrawdownPct","-0.10");
        var d=json.createObjectNode();var item=d.putArray("items").addObject();item.put("item","DRAWDOWN").put("verdict","CHANGED").put("dataAsOfDate","2026-11-13");item.putObject("facts").put("current_drawdown","-0.12");
        assertEquals("REDUCE",policy.decide("123456",p,rating(),review("UP","UP"),d,now).path("decision").asText());
        item.put("dataAsOfDate","2026-11-12");assertEquals("HOLD",policy.decide("123456",p,rating(),review("UP","UP"),d,now).path("decision").asText());
    }
    @Test void repeatedFactsDoNotAccumulateVotes() throws Exception {
        var result=decide(personal(),rating(),review("UP","UP"));assertEquals(1,result.path("supportingEvidence").size());
    }
    @Test void wrongFundAndOutdatedValuationStopJudgment() throws Exception {
        var v=review("UP","UP");v.put("fundCode","654321");assertTrue(decide(personal(),rating(),v).path("decision").isNull());
        var p=personal();((ObjectNode)p.path("position")).put("navDate","2026-11-12");assertTrue(decide(p,rating(),review("UP","UP")).path("decision").isNull());
    }
    @Test void clearedHoldingNeverGetsNewAction() throws Exception {
        var p=personal();((ObjectNode)p.path("position")).put("shares","0");assertTrue(decide(p,rating(),review("UP","UP")).path("decision").isNull());
    }
}
