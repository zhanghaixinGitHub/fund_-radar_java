package com.fundradar.core.portfolio;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.fundradar.core.portfolio.AccountFundingTypes.*;
import static org.junit.jupiter.api.Assertions.*;

/** 候选路径不得因没有输入而默认持有，也不得用假设跌幅制造实际回撤或真实费用。 */
class AccountOptionsTests {
    final LocalDate today=LocalDate.of(2026,9,29);
    final AccountRiskService.Holding holding=new AccountRiskService.Holding("000001","合成基金",new BigDecimal("1000"),null,null);
    AccountRiskService.Result account(String decline) {
        return AccountRiskService.calculate(Scope.CONFIRMED,List.of(holding),Map.of(),decline==null?null:new BigDecimal(decline));
    }
    View setting(String amount,String willing,String affordable,LocalDate date) {
        return new View(UUID.randomUUID(),Scope.CONFIRMED,1,"ACTIVE",new Input(null,date,amount==null?null:new BigDecimal(amount),
                willing==null?null:new BigDecimal(willing),affordable==null?null:new BigDecimal(affordable),null),Instant.parse("2026-09-29T08:00:00Z"));
    }
    @Test void missingDataCannotBecomeHoldOrZeroFeeOrExactTradeDate() {
        var result=AccountOptionsService.assemble(account(null),holding,null,today);
        assertNull(result.advice());assertEquals(4,result.paths().size());
        for(var path:result.paths()) {
            assertNull(path.estimatedFee());assertNull(path.executableShares());assertNull(path.earliestExecutionDate());assertFalse(path.missing().isEmpty());
            assertFalse(path.opposingReasons().isEmpty());assertFalse(path.reviewConditions().isEmpty());
        }
        assertTrue(result.paths().get(3).reentryConditions().stream().anyMatch(s->s.contains("已实际到账")));
        assertTrue(result.paths().get(3).reentryConditions().stream().anyMatch(s->s.contains("最短观察间隔")));
    }
    @Test void willingLossAndAffordableAmountRemainDifferentConditions() {
        var checks=AccountOptionsService.personalChecks(setting(null,"10","50",today.plusDays(1)),account("10"),today);
        assertTrue(checks.stream().anyMatch(s->s.contains("10%") && s.contains("历史账户回撤不可比")));
        assertTrue(checks.stream().anyMatch(s->s.contains("100.00元，高于本人现实可承担金额")));
        assertTrue(checks.stream().anyMatch(s->s.contains("不是发生概率")));
    }
    @Test void zeroToleranceIsRealInputButUnknownDoesNotBecomeZero() {
        var zero=AccountOptionsService.personalChecks(setting(null,"0","0",null),account("1"),today);
        assertTrue(zero.stream().anyMatch(s->s.contains("0%")));
        assertTrue(zero.stream().anyMatch(s->s.contains("高于本人现实可承担金额")));
        var unknown=AccountOptionsService.personalChecks(setting(null,null,null,null),account(null),today);
        assertTrue(unknown.stream().anyMatch(s->s.contains("损失金额尚未确定")));
        assertFalse(unknown.stream().anyMatch(s->s.contains("损失0")));
    }
    @Test void reachedUseDateDoesNotAutoSellAndRecordedAssetsAreNotAvailableCash() {
        var checks=AccountOptionsService.personalChecks(setting("2000",null,null,today),account(null),today);
        assertTrue(checks.stream().anyMatch(s->s.contains("不据此自动卖出")));
        assertTrue(checks.stream().anyMatch(s->s.contains("不能把差额当成实际资金缺口")));
    }
    @Test void revokedSettingCannotStillConstrainDecision() {
        var revoked=new View(UUID.randomUUID(),Scope.CONFIRMED,2,"REVOKED",null,Instant.now());
        assertEquals(1,AccountOptionsService.personalChecks(revoked,account("10"),today).size());
        assertNull(AccountOptionsService.assemble(account("10"),holding,revoked,today).advice());
    }
}
