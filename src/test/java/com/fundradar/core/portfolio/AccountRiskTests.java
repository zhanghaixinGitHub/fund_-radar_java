package com.fundradar.core.portfolio;

import com.fundradar.core.fund.api.FundRiskSummaryResponse;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static com.fundradar.core.portfolio.AccountFundingTypes.Scope;
import static org.junit.jupiter.api.Assertions.*;

/** 检查已知分母、不完整覆盖、未来披露与未知日期，测试金额均为合成值。 */
class AccountRiskTests {
    BigDecimal n(String v) { return new BigDecimal(v); }
    AccountRiskService.Holding holding(String code,String value,LocalDate day) {
        return new AccountRiskService.Holding(code,"测试基金",n(value),day,null);
    }
    FundRiskSummaryResponse risk(String code,String published,String denominator,List<FundRiskSummaryResponse.Holding> stocks) {
        return new FundRiskSummaryResponse(code,true,"2026-09-28","2026-06-30",published,"https://example.org/report",
                List.of(),stocks,List.of(new FundRiskSummaryResponse.Allocation("制造业",n("60"),denominator)),List.of(),null,List.of(),n("60"),n("60"),null);
    }
    @Test void partialCoverageDoesNotRenormalizeAndSameCompanyIsMerged() {
        var stocks=List.of(new FundRiskSummaryResponse.Holding("300308.SZ","测试公司",n("60")));
        var result=AccountRiskService.calculate(Scope.CONFIRMED,List.of(holding("000001","100",LocalDate.of(2026,9,28)),holding("000002","100",LocalDate.of(2026,9,28))),
                Map.of("000001",risk("000001","2026-08-31","基金净资产",stocks)),n("20"));
        assertEquals(0,result.coveredFundWeightPct().compareTo(n("50")));
        assertEquals(0,result.knownLookThroughWeightPct().compareTo(n("30")));
        assertEquals(0,result.companies().get(0).weightPct().compareTo(n("30")));
        assertEquals(0,result.industries().get(0).weightPct().compareTo(n("30")));
        assertEquals(n("40.00"),result.scenarioLoss());
        result=AccountRiskService.calculate(Scope.CONFIRMED,List.of(holding("000001","100",LocalDate.of(2026,9,28)),holding("000002","100",LocalDate.of(2026,9,28))),
                Map.of("000001",risk("000001","2026-08-31","基金净资产",stocks),"000002",risk("000002","2026-08-31","基金净资产",stocks)),null);
        assertEquals(1,result.companies().size());assertEquals(0,result.companies().get(0).weightPct().compareTo(n("60")));
        assertNull(result.scenarioLoss());
    }
    @Test void grossAssetsAreNotMixedIntoNetAssetIndustries() {
        var result=AccountRiskService.calculate(Scope.CONFIRMED,List.of(holding("000001","100",LocalDate.of(2026,9,28))),
                Map.of("000001",risk("000001","2026-08-31","基金总资产",List.of())),null);
        assertTrue(result.industries().isEmpty());
    }
    @Test void futureDisclosureUnknownDateWrongFundOrMissingDataRemainUnknown() {
        var stocks=List.of(new FundRiskSummaryResponse.Holding("300308.SZ","测试公司",n("60")));
        for(var holding:List.of(holding("000001","100",LocalDate.of(2026,8,30)),holding("000001","100",null))) {
            var result=AccountRiskService.calculate(Scope.CONFIRMED,List.of(holding),Map.of("000001",risk("000001","2026-08-31","基金净资产",stocks)),null);
            assertTrue(result.companies().isEmpty());assertNull(result.knownLookThroughWeightPct());
        }
        var wrong=AccountRiskService.calculate(Scope.CONFIRMED,List.of(holding("000001","100",LocalDate.of(2026,9,28))),
                Map.of("000001",risk("000002","2026-08-31","基金净资产",stocks)),null);
        assertNull(wrong.knownLookThroughWeightPct());
        var empty=AccountRiskService.calculate(Scope.SIMULATED,List.of(),Map.of(),n("20"));
        assertNull(empty.recordedAmount());assertNull(empty.scenarioLoss());
    }
    @Test void duplicateAndOverweightDisclosuresAreExcluded() {
        var h=new FundRiskSummaryResponse.Holding("300308.SZ","测试公司",n("60"));
        for(var stocks:List.of(List.of(h,h),List.of(new FundRiskSummaryResponse.Holding("300308.SZ","测试公司",n("101"))))) {
            var result=AccountRiskService.calculate(Scope.CONFIRMED,List.of(holding("000001","100",LocalDate.of(2026,9,28))),
                    Map.of("000001",risk("000001","2026-08-31","基金净资产",stocks)),null);
            assertNull(result.knownLookThroughWeightPct());assertTrue(result.sources().isEmpty());
        }
    }
}
