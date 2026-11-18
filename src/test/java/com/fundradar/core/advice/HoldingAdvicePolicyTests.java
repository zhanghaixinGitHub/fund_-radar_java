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
        {"position":{"shares":"100","availableShares":"100","frozenShares":"0","holdingGainRate":"0.12","navDate":"2026-11-13"},"orders":[],"plans":[],"rule":null,"preference":"BALANCED",
        "portfolioComplete":true,"portfolio":[{"fundCode":"123456","cost":"1000","holdingGain":"120","marketValue":"1120","navDate":"2026-11-13"}],
        "funding":{"status":"ACTIVE","confirmedAt":"2026-11-15T10:00:00+08:00","input":{"requiredAmount":"0","willingLossPct":"20","affordableLossAmount":"200"}}}
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
    @Test void confirmedFundingDateArrayMatchesIsoDate() throws Exception {
        var iso=personal();var input=(ObjectNode)iso.path("funding").path("input");
        input.put("requiredAmount","10000").put("useDate","2027-01-31");
        var array=iso.deepCopy();((ObjectNode)array.path("funding").path("input"))
            .set("useDate",json.readTree("[2027,1,31]"));
        var expected=decide(iso,rating(),review("UP","UP"));
        var actual=decide(array,rating(),review("UP","UP"));
        assertEquals("HOLD",actual.path("decision").asText());
        assertEquals(expected.path("missingOptionalFactors"),actual.path("missingOptionalFactors"));
        assertEquals(expected.path("personalAssessment"),actual.path("personalAssessment"));
    }
    @Test void nearTermFundingDateArrayStillPreventsUnsupportedHolding() throws Exception {
        var p=personal();var input=(ObjectNode)p.path("funding").path("input");
        input.put("requiredAmount","10000");input.set("useDate",json.readTree("[2026,11,20]"));
        var actual=decide(p,rating(),review("UP","UP"));
        assertTrue(actual.path("decision").isNull());
        assertTrue(actual.path("missingOptionalFactors").toString().contains("现金覆盖及赎回到账日期"));
    }
    @Test void malformedFundingDatesRemainUnknown() throws Exception {
        for(String value:new String[]{"[2026,2,30]","[2027,1]","[2027,1,31,0]","[2027,1.5,31]","[\"2027\",1,31]","null"}) {
            var p=personal();var input=(ObjectNode)p.path("funding").path("input");
            input.put("requiredAmount","10000");input.set("useDate",json.readTree(value));
            var actual=decide(p,rating(),review("UP","UP"));
            assertTrue(actual.path("decision").isNull(),value);
            assertTrue(actual.path("missingOptionalFactors").toString().contains("用款日期尚未确认"),value);
        }
    }
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
        assertTrue(decide(p,rating(),review("DOWN","DOWN")).path("decision").isNull());
        p=personal();((ObjectNode)p.path("position")).put("availableShares","0").put("frozenShares","100");
        assertTrue(decide(p,rating(),review("DOWN","DOWN")).path("decision").isNull());
    }
    @Test void confirmedRuleUsesDecimalRatioNotPercent() throws Exception {
        var p=personal();p.putObject("rule").put("takeProfitPct","0.10");
        assertEquals("REDUCE",decide(p,rating(),review("UP","UP")).path("decision").asText());
        ((ObjectNode)p.path("position")).put("holdingGainRate","0.0012");
        assertEquals("HOLD",decide(p,rating(),review("UP","UP")).path("decision").asText());
    }
    @Test void holdingLossReminderUsesPositionGainWithoutDrawdownFact() throws Exception {
        var p=personal();p.putObject("rule").put("reduceDrawdownPct","-0.10").put("downsideBasis","HOLDING_LOSS");
        ((ObjectNode)p.path("position")).put("holdingGainRate","-0.10");
        var result=decide(p,rating(),review("UP","UP"));
        assertEquals("REDUCE",result.path("decision").asText());
        assertTrue(result.path("opposingEvidence").toString().contains("持仓亏损提醒"));
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

    private ObjectNode withHalf(ObjectNode review,String kind,String direction) {
        var period=((ObjectNode)review.path("periods").get(1)).deepCopy();
        period.put("horizonId","M6_V1").put("kind",kind).put("decision",direction).put("endDate","2027-05-14");
        review.withArray("periods").add(period);return review;
    }
    @Test void preferencesActuallyChangeTheSameConflictDecision() throws Exception {
        var p=personal();var v=review("DOWN","UP");
        p.put("preference","SHORT");assertEquals("REDUCE",decide(p,rating(),v).path("decision").asText());
        p.put("preference","BALANCED");assertEquals("HOLD",decide(p,rating(),v).path("decision").asText());
        p.put("preference","LONG");withHalf(v,"DATED_ANALYSIS","UP");
        assertEquals("HOLD",decide(p,rating(),v).path("decision").asText());
    }
    @Test void longPreferenceRequiresRealHalfYearEvidenceAndBothPrimaryDownToReduce() throws Exception {
        var p=personal();p.put("preference","LONG");
        assertTrue(decide(p,rating(),review("DOWN","DOWN")).path("decision").isNull());
        assertTrue(decide(p,rating(),withHalf(review("DOWN","DOWN"),"CONDITIONAL_OUTLOOK","UP")).path("decision").isNull());
        assertEquals("HOLD",decide(p,rating(),withHalf(review("DOWN","DOWN"),"DATED_ANALYSIS","UP")).path("decision").asText());
        assertEquals("REDUCE",decide(p,rating(),withHalf(review("UP","DOWN"),"DATED_ANALYSIS","DOWN")).path("decision").asText());
    }
    @Test void missingPersonalPreferenceIsNotAssumedBalanced() throws Exception {
        var p=personal();p.remove("preference");
        assertTrue(decide(p,rating(),review("UP","UP")).path("decision").isNull());
    }
    @Test void absentRevokedAndFutureFundingStopHoldingButPreserveRiskReduction() throws Exception {
        for(String state:new String[]{"ABSENT","REVOKED","FUTURE"}) {
            var p=personal();
            if(state.equals("ABSENT")) p.remove("funding");
            else if(state.equals("REVOKED")) ((ObjectNode)p.path("funding")).put("status","REVOKED");
            else ((ObjectNode)p.path("funding")).put("confirmedAt","2026-11-17T10:00:00+08:00");
            assertTrue(decide(p,rating(),review("UP","UP")).path("decision").isNull());
            var reduce=decide(p,rating(),review("DOWN","DOWN"));
            assertEquals("REDUCE",reduce.path("decision").asText());assertEquals("QUALIFIED",reduce.path("adviceState").asText());
        }
    }
    @Test void upcomingUseOfFundsBlocksHoldingEvenWhenBothPeriodsAreUp() throws Exception {
        var p=personal();var input=(ObjectNode)p.path("funding").path("input");
        input.put("requiredAmount","500").put("useDate","2026-11-20");
        assertTrue(decide(p,rating(),review("UP","UP")).path("decision").isNull());
        input.put("useDate","2026-12-01");assertEquals("HOLD",decide(p,rating(),review("UP","UP")).path("decision").asText());
        input.remove("useDate");assertTrue(decide(p,rating(),review("UP","UP")).path("decision").isNull());
        input.put("requiredAmount","0");assertEquals("HOLD",decide(p,rating(),review("UP","UP")).path("decision").asText());
    }
    @Test void unconfirmedUseAmountAndOverdueReviewBlockHolding() throws Exception {
        var p=personal();var input=(ObjectNode)p.path("funding").path("input");
        input.remove("requiredAmount");assertTrue(decide(p,rating(),review("UP","UP")).path("decision").isNull());
        input.put("requiredAmount","0").put("reviewIntervalDays",1);
        assertTrue(decide(p,rating(),review("UP","UP")).path("decision").isNull());
    }
    @Test void actualAccountLossTriggersConfirmedAmountOrPercentLimitDespiteBullishViews() throws Exception {
        var p=personal();var holding=(ObjectNode)p.path("portfolio").get(0);holding.put("holdingGain","-210");
        ((ObjectNode)p.path("funding").path("input")).put("willingLossPct","50");
        assertEquals("REDUCE",decide(p,rating(),review("UP","UP")).path("decision").asText());
        holding.put("holdingGain","-110");((ObjectNode)p.path("funding").path("input")).put("willingLossPct","10").put("affordableLossAmount","500");
        assertEquals("REDUCE",decide(p,rating(),review("UP","UP")).path("decision").asText());
        holding.put("holdingGain","-99");assertEquals("HOLD",decide(p,rating(),review("UP","UP")).path("decision").asText());
    }
    @Test void incompleteAccountValuesCannotClaimLossCapacityPassed() throws Exception {
        var p=personal();((ObjectNode)p.path("portfolio").get(0)).remove("cost");
        assertTrue(decide(p,rating(),review("UP","UP")).path("decision").isNull());
        p=personal();((ObjectNode)p.path("portfolio").get(0)).put("navDate","2026-11-12");
        var second=p.withArray("portfolio").addObject();second.put("cost","100").put("holdingGain","0");
        assertTrue(decide(p,rating(),review("UP","UP")).path("decision").isNull());
    }
    @Test void fundingLimitsCannotBypassClearedHoldingOrFrozenSellRestrictions() throws Exception {
        var p=personal();((ObjectNode)p.path("portfolio").get(0)).put("holdingGain","-300");
        ((ObjectNode)p.path("position")).put("availableShares","0");
        assertTrue(decide(p,rating(),review("UP","UP")).path("decision").isNull());
        ((ObjectNode)p.path("position")).put("shares","0");
        assertTrue(decide(p,rating(),review("UP","UP")).path("decision").isNull());
    }

    @Test void oneKnownLossLimitRemainsBindingWhenTheOtherIsUnknown() throws Exception {
        var p=personal();((ObjectNode)p.path("portfolio").get(0)).put("holdingGain","-300");
        ((ObjectNode)p.path("funding").path("input")).remove("willingLossPct");
        var result=decide(p,rating(),review("UP","UP"));
        assertEquals("REDUCE",result.path("decision").asText());assertEquals("QUALIFIED",result.path("adviceState").asText());
    }

    @Test void knownPersonalLossLimitDoesNotDisappearWhenPublicInputsAreMissing() throws Exception {
        var p=personal();((ObjectNode)p.path("portfolio").get(0)).put("holdingGain","-300");
        var result=policy.decide("123456",p,json.nullNode(),json.nullNode(),json.nullNode(),now);
        assertEquals("REDUCE",result.path("decision").asText());assertEquals("QUALIFIED",result.path("adviceState").asText());
    }

    @Test void latestKnownValuationsMatchPortfolioPageWithoutInventingSameDayDrawdown() throws Exception {
        var p=personal();var second=p.withArray("portfolio").addObject();
        second.put("cost","100").put("holdingGain","0").put("navDate","2026-11-12");
        var result=decide(p,rating(),review("UP","UP"));
        assertEquals("HOLD",result.path("decision").asText());
        assertTrue(result.path("personalAssessment").asText().contains("各基金净值日期不同"));
    }
}
