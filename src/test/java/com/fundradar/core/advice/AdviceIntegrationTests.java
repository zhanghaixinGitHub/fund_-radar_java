package com.fundradar.core.advice;

import com.fundradar.core.auth.*;
import com.fundradar.core.auth.service.AccessDeniedException;
import com.fundradar.core.simulation.*;
import com.fundradar.core.watchlist.api.DirectionExperimentResponse;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import static com.fundradar.core.advice.AdviceTypes.*;
import static org.junit.jupiter.api.Assertions.*;

/** 在真实PostgreSQL隔离事务中验证归属、不可变历史与核验去重；所有测试用户和报告自动回滚。 */
@SpringBootTest(properties={"simulation.enabled=false","portfolio.advice.enabled=false"})
@Transactional
class AdviceIntegrationTests {
    @Autowired AdviceService service;
    @Autowired AdviceRepository repo;
    @Autowired AdvicePolicy policy;
    @Autowired SimulationRepository positions;
    @Autowired JdbcClient db;
    UUID user;
    Instant now=Instant.parse("2026-09-11T02:00:00Z");
    SimulationCalendar calendar;
    @BeforeEach void setup() {
        user=createUser(); login(user,true);
        var days=LocalDate.of(2026,9,1).datesUntil(LocalDate.of(2026,12,31)).filter(d->d.getDayOfWeek().getValue()<6)
                .filter(d->!(d.getMonthValue()==10 && d.getDayOfMonth()<=7)).toList();
        calendar=new SimulationCalendar(new SimulationTypes.CalendarData("test-calendar",days.get(0),LocalDate.of(2026,12,31),days,"test"));
        positions.lock(user);
        db.sql("INSERT INTO sim_position(user_id,fund_code,snapshot,updated_at) VALUES (:user,'006730',CAST(:data AS jsonb),now())")
                .param("user",user).param("data",positions.encode(position(BigDecimal.ONE))).update();
    }
    @AfterEach void clear() { CurrentUserContext.clear(); }
    UUID createUser() {
        UUID id=UUID.randomUUID();
        db.sql("INSERT INTO user_account(user_id,mobile,display_name,password_hash,role,status) VALUES(:id,:mobile,'建议集成测试','test-only-hash','FUND_USER','ACTIVE')")
                .param("id",id).param("mobile","139"+String.format("%08d",ThreadLocalRandom.current().nextInt(100000000))).update();
        return id;
    }
    void login(UUID id,boolean write) {
        CurrentUserContext.set(new AuthenticatedUser(id,"test","建议测试",AccountRole.FUND_USER,
                write ? Set.of(PermissionCode.PORTFOLIO_SELF_READ,PermissionCode.SIM_PORTFOLIO_SELF_WRITE) : Set.of(PermissionCode.PORTFOLIO_SELF_READ)));
    }
    SimulationTypes.Position position(BigDecimal shares) {
        var z=BigDecimal.ZERO; var o=BigDecimal.ONE;
        return new SimulationTypes.Position("006730","建议测试基金",shares,z,shares,o,o,z,z,z,z,z,z,z,z,o,z,o,LocalDate.of(2026,9,10),null);
    }
    DirectionExperimentResponse experiment(double main,double reference,Instant read) {
        return new DirectionExperimentResponse("006730","DIRECTION_PAGE_TRIAL_V1","EXPERIMENTAL",false,20,UUID.randomUUID(),
                LocalDate.of(2026,9,10),LocalDate.of(2026,9,9),LocalDate.of(2026,9,10),LocalDate.of(2026,10,16),read,
                "a".repeat(64),UUID.fromString("00000000-0000-0000-0000-000000000001"),List.of(
                    new DirectionExperimentResponse.ModelScore("DROP_60D_GROUP_L2",main,main>0.5?"UP":"NON_UP","b".repeat(64),LocalDate.of(2024,3,31)),
                    new DirectionExperimentResponse.ModelScore("REFERENCE",reference,reference>0.5?"UP":"NON_UP","c".repeat(64),LocalDate.of(2024,3,31))),List.of(),"test");
    }
    UUID save(DirectionExperimentResponse input,Instant time) { return service.archive(user,position(BigDecimal.ONE),input,calendar,time); }
    @Test void policy_has_hold_sell_counter_evidence_and_no_position_states() {
        assertEquals("HOLD",policy.build(position(BigDecimal.ONE),experiment(.7,.8,now),calendar,now).decision());
        var sell=policy.build(position(BigDecimal.ONE),experiment(.5,.8,now),calendar,now);
        assertEquals("SELL",sell.decision());
        assertEquals("AGAINST",sell.evidence().get(1).relation());
        assertEquals("UNAVAILABLE",policy.build(position(BigDecimal.ZERO),null,calendar,now).decision());
        assertEquals("UNAVAILABLE",policy.build(position(BigDecimal.ONE),DirectionExperimentResponse.unavailable("006730"),calendar,now).decision());
    }
    @Test void refresh_is_idempotent_daily_carry_is_not_an_independent_sample() {
        var input=experiment(.7,.3,now);
        UUID first=save(input,now);
        assertEquals(first,save(input,now.plusSeconds(300)));
        UUID tomorrow=save(input,now.plusSeconds(86400));
        assertNotEquals(first,tomorrow);
        assertEquals(first,service.detail("006730",tomorrow).report().originalReportId());
        assertEquals(now,service.detail("006730",first).report().generatedAt());
        var result=service.history("006730",1,20,null,null,AdvicePolicy.VERSION);
        assertEquals(2,result.reports().totalCount()); assertEquals(1,result.stats().samples()); assertEquals(1,result.stats().carried());
    }
    @Test void changed_input_adds_a_version_without_rewriting_original() {
        UUID first=save(experiment(.7,.3,now),now);
        UUID second=save(experiment(.3,.7,now),now.plusSeconds(300));
        assertNotEquals(first,second);
        assertEquals("HOLD",service.detail("006730",first).snapshot().decision());
        assertEquals("SELL",service.detail("006730",second).snapshot().decision());
        assertEquals(2,service.history("006730",1,20,null,null,AdvicePolicy.VERSION).stats().samples());
    }
    @Test void different_user_cannot_list_read_or_generate_another_users_fund() {
        UUID first=save(experiment(.7,.3,now),now);
        login(createUser(),true);
        assertTrue(service.latest().isEmpty());
        assertThrows(SimulationException.class,()->service.history("006730",1,20,null,null,AdvicePolicy.VERSION));
        assertThrows(SimulationException.class,()->service.detail("006730",first));
        assertThrows(SimulationException.class,()->service.generate("006730"));
        login(user,false);
        assertThrows(AccessDeniedException.class,()->service.generate("006730"));
    }
    @Test void future_assessment_is_separate_and_final_results_are_not_overwritten() {
        UUID first=save(experiment(.3,.8,now),now);
        var report=service.detail("006730",first).report();
        var pending=new Pending(first,"006730","SELL",report.observationStart(),report.observationEnd());
        var value=new Outcome("006730",pending.start(),pending.end(),"ASSESSED",now.plusSeconds(40*86400),new BigDecimal("-0.1"),
                "test outcome","CASH_REINVESTMENT_20D_V1","TUSHARE_PRO_FUND","d".repeat(64),Map.of("nav","test"));
        repo.review(pending,value);
        assertEquals("SUPPORTED",service.detail("006730",first).report().support());
        repo.review(pending,new Outcome("006730",pending.start(),pending.end(),"DATA_INSUFFICIENT",now,null,"later missing","CASH_REINVESTMENT_20D_V1","TUSHARE_PRO_FUND",null,null));
        assertEquals(new BigDecimal("-0.100000000000"),service.detail("006730",first).report().totalReturn());
        assertEquals("SELL",service.detail("006730",first).snapshot().decision());
        var stats=service.history("006730",1,20,null,null,AdvicePolicy.VERSION).stats();
        assertEquals(1,stats.assessed()); assertEquals(1,stats.supported()); assertEquals(0,stats.pending());
    }
    @Test void date_filter_pagination_and_no_advice_counts_are_exact() {
        var input=experiment(.7,.3,now); save(input,now); save(input,now.plusSeconds(86400));
        service.archive(user,position(BigDecimal.ZERO),null,calendar,now.plusSeconds(2*86400));
        var first=service.history("006730",1,1,null,null,AdvicePolicy.VERSION);
        assertEquals(3,first.reports().totalCount()); assertEquals(1,first.reports().items().size()); assertEquals(1,first.stats().noAdvice());
        assertEquals(1,service.history("006730",1,20,LocalDate.of(2026,9,11),LocalDate.of(2026,9,11),AdvicePolicy.VERSION).reports().totalCount());
        assertThrows(SimulationException.class,()->service.history("006730",0,100,null,null,AdvicePolicy.VERSION));
    }
    @Test void observation_starts_after_actual_generation_and_skips_weekends() {
        var input=experiment(.7,.3,now);
        var afterCutoff=policy.build(position(BigDecimal.ONE),input,calendar,Instant.parse("2026-09-11T08:00:00Z"));
        assertEquals(LocalDate.of(2026,9,14),afterCutoff.observationStart());
        assertTrue(afterCutoff.observationStart().isAfter(input.cutoffDate()));
    }
    @Test void database_rejects_rewriting_archived_report() {
        UUID first=save(experiment(.7,.3,now),now);
        assertThrows(org.springframework.dao.DataAccessException.class,()->db.sql("UPDATE portfolio_advice_report SET summary='rewritten' WHERE report_id=:id").param("id",first).update());
    }
}
