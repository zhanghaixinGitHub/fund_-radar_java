package com.fundradar.core.portfolio;

import com.fundradar.core.auth.*;
import com.fundradar.core.simulation.SimulationException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import static com.fundradar.core.portfolio.AccountFundingTypes.*;
import static org.junit.jupiter.api.Assertions.*;

/** 在独立测试 schema 内验证本人隔离和不可变确认历史；所有合成数据在测试后回滚。 */
@SpringBootTest(properties={"direction1d.initial-delay=P365D","prediction.multi.initial-delay=P365D",
        "direction1d.training-enabled=false","direction1d.review-enabled=false",
        "simulation.enabled=false","portfolio.advice.enabled=false"})
@Transactional
class AccountFundingIntegrationTests {
    @Autowired AccountFundingService service;
    @Autowired AccountRiskService risks;
    @Autowired AccountOptionsService options;
    @Autowired JdbcClient db;
    UUID user,other;
    @BeforeEach void setup() { user=createUser();other=createUser();login(user,true); }
    @AfterEach void clear() { CurrentUserContext.clear(); }
    UUID createUser() {
        UUID id=UUID.randomUUID();
        db.sql("INSERT INTO user_account(user_id,mobile,display_name,password_hash,role,status) VALUES(:id,:m,'本人安排隔离测试','synthetic-only','FUND_USER','ACTIVE')")
                .param("id",id).param("m","139"+String.format("%08d",ThreadLocalRandom.current().nextInt(100000000))).update();
        return id;
    }
    void login(UUID id,boolean write) {
        CurrentUserContext.set(new AuthenticatedUser(id,"test","测试",AccountRole.FUND_USER,
                write ? Set.of(PermissionCode.PORTFOLIO_SELF_READ,PermissionCode.ALERT_RULE_SELF_WRITE) : Set.of(PermissionCode.PORTFOLIO_SELF_READ)));
    }
    Input unknown() { return new Input(null,null,null,null,null,null); }
    Change change(long version,Input input) { return new Change(UUID.randomUUID(),version,true,input); }
    @Test void unknownStaysNullAndNoReadWrites() {
        assertNull(service.read(Scope.CONFIRMED,1).current());
        assertNull(service.read(Scope.SIMULATED,1).current());
        var saved=service.change(Scope.CONFIRMED,change(0,unknown()),false);
        assertNull(saved.input().willingLossPct());assertNull(saved.input().requiredAmount());
        assertEquals(1,service.read(Scope.CONFIRMED,1).history().size());
    }
    @Test void otherAccountAndSimulationCannotReadOrReplaceRealSettings() {
        var first=service.change(Scope.CONFIRMED,change(0,new Input("测试用途",null,new BigDecimal("200"),null,null,null)),false);
        assertNull(service.read(Scope.SIMULATED,1).current());
        login(other,true);
        assertNull(service.read(Scope.CONFIRMED,1).current());
        assertThrows(SimulationException.class,()->service.change(Scope.CONFIRMED,change(first.revision(),unknown()),false));
        service.change(Scope.CONFIRMED,change(0,unknown()),false);
        login(user,true);
        assertEquals(first,service.read(Scope.CONFIRMED,1).current());
    }
    @Test void requestRetryIsIdempotentAndChangedPayloadRejected() {
        Change request=change(0,unknown());
        var first=service.change(Scope.CONFIRMED,request,false);
        assertEquals(first,service.change(Scope.CONFIRMED,request,false));
        assertThrows(SimulationException.class,()->service.change(Scope.SIMULATED,request,false));
        assertEquals(1,service.read(Scope.CONFIRMED,1).history().size());
    }
    @Test void staleConfirmationRejectedAndRevocationAppendsHistory() {
        var first=service.change(Scope.CONFIRMED,change(0,unknown()),false);
        assertThrows(SimulationException.class,()->service.change(Scope.CONFIRMED,change(0,unknown()),false));
        Change revoke=change(1,null);
        var result=service.change(Scope.CONFIRMED,revoke,true);
        assertEquals("REVOKED",result.status());assertNull(result.input());
        assertEquals(result,service.change(Scope.CONFIRMED,revoke,true));
        assertEquals(first,service.read(Scope.CONFIRMED,1).history().get(1));
    }
    @Test void requiresLoginWritePermissionAndExplicitConfirmation() {
        CurrentUserContext.clear();
        assertThrows(RuntimeException.class,()->service.read(Scope.CONFIRMED,1));
        login(user,false);
        assertThrows(RuntimeException.class,()->service.change(Scope.CONFIRMED,change(0,unknown()),false));
        login(user,true);
        assertThrows(IllegalArgumentException.class,()->service.change(Scope.CONFIRMED,new Change(UUID.randomUUID(),0,false,unknown()),false));
        assertNull(service.read(Scope.CONFIRMED,1).current());
    }
    @Test void invalidInputsCannotBeSavedOrExpandedIntoZeroValues() {
        for(Input input:List.of(new Input("x".repeat(161),null,null,null,null,null),
                new Input(null,null,new BigDecimal("1E99"),null,null,null),
                new Input(null,null,null,new BigDecimal("100.01"),null,null),
                new Input(null,null,null,null,new BigDecimal("-1"),null),
                new Input(null,null,null,null,null,0)))
            assertThrows(IllegalArgumentException.class,()->service.change(Scope.CONFIRMED,change(0,input),false));
        assertNull(service.read(Scope.CONFIRMED,1).current());
        assertThrows(IllegalArgumentException.class,()->service.read(Scope.CONFIRMED,0));
    }
    @Test void historyIsBoundedAndStable() {
        for(int n=0;n<22;n++) service.change(Scope.SIMULATED,change(n,unknown()),false);
        var first=service.read(Scope.SIMULATED,1);var second=service.read(Scope.SIMULATED,2);
        assertEquals(20,first.history().size());assertTrue(first.hasMore());
        assertEquals(2,second.history().size());assertFalse(second.hasMore());
        assertEquals(22,first.current().revision());
        assertEquals(2,second.history().get(0).revision());
    }
    @Test void databaseRejectsMutationOfPriorConfirmation() {
        service.change(Scope.CONFIRMED,change(0,unknown()),false);
        assertThrows(RuntimeException.class,()->db.sql("UPDATE account_funding_preference SET status='REVOKED' WHERE user_id=:u").param("u",user).update());
    }
    @Test void riskUsesOnlyOwnConfirmedSnapshotAndDoesNotFallBackToSimulation() {
        UUID snapshot=UUID.randomUUID();
        db.sql("INSERT INTO portfolio_snapshot(snapshot_id,user_id,source_kind,data_as_of_date,data_as_of_status,source_description,source_content_hash) VALUES(:s,:u,'USER_CONFIRMED_SCREENSHOT','2026-09-28','KNOWN','合成测试',:h)")
                .param("s",snapshot).param("u",user).param("h","b".repeat(64)).update();
        db.sql("INSERT INTO portfolio_holding_snapshot(holding_snapshot_id,snapshot_id,fund_code,fund_name,reported_amount,reported_weight_pct,reported_daily_gain_amount,reported_holding_gain_amount,reported_holding_gain_pct,reported_cumulative_gain_amount) VALUES(:id,:s,'000001','合成基金',100,100,0,0,0,0)")
                .param("id",UUID.randomUUID()).param("s",snapshot).update();
        assertEquals(new BigDecimal("100.00"),risks.read(Scope.CONFIRMED,null).recordedAmount());
        assertNull(risks.read(Scope.SIMULATED,null).recordedAmount());
        assertNull(options.read(Scope.CONFIRMED,"000001",null).advice());
        assertThrows(com.fundradar.core.notification.service.NotificationNotFoundException.class,()->options.read(Scope.SIMULATED,"000001",null));
        assertThrows(IllegalArgumentException.class,()->options.read(Scope.CONFIRMED,"",null));
        login(other,true);
        assertNull(risks.read(Scope.CONFIRMED,null).recordedAmount());
        assertThrows(com.fundradar.core.notification.service.NotificationNotFoundException.class,()->options.read(Scope.CONFIRMED,"000001",null));
        CurrentUserContext.clear();
        assertThrows(RuntimeException.class,()->risks.read(Scope.CONFIRMED,null));
    }
}
