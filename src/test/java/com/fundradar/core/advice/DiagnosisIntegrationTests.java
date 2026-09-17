package com.fundradar.core.advice;

import com.fundradar.core.auth.*;
import com.fundradar.core.auth.service.AccessDeniedException;
import com.fundradar.core.simulation.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import static com.fundradar.core.advice.DiagnosisTypes.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真实PostgreSQL隔离事务中验证七项终判、基线对比、同日幂等、跨用户拒绝与不可改写；所有测试数据自动回滚。 */
@SpringBootTest(properties={"simulation.enabled=false","portfolio.advice.enabled=false"})
@Transactional
class DiagnosisIntegrationTests {
    @Autowired DiagnosisService service;
    @Autowired DiagnosisRepository repo;
    @Autowired SimulationRepository positions;
    @Autowired JdbcClient db;
    UUID user;
    Instant day1=Instant.parse("2026-09-11T02:00:00Z");
    @BeforeEach void setup() {
        user=createUser(); login(user,Set.of(PermissionCode.PORTFOLIO_SELF_READ));
        positions.lock(user);
        db.sql("INSERT INTO sim_position(user_id,fund_code,snapshot,updated_at) VALUES (:user,'006730',CAST(:data AS jsonb),now())")
                .param("user",user).param("data",positions.encode(position("006730","诊断测试基金"))).update();
    }
    @AfterEach void clear() { CurrentUserContext.clear(); }
    UUID createUser() {
        UUID id=UUID.randomUUID();
        db.sql("INSERT INTO user_account(user_id,mobile,display_name,password_hash,role,status) VALUES(:id,:mobile,'诊断集成测试','test-only-hash','FUND_USER','ACTIVE')")
                .param("id",id).param("mobile","139"+String.format("%08d",ThreadLocalRandom.current().nextInt(100000000))).update();
        return id;
    }
    void login(UUID id,Set<PermissionCode> permissions) {
        CurrentUserContext.set(new AuthenticatedUser(id,"test","诊断测试",AccountRole.FUND_USER,permissions));
    }
    SimulationTypes.Position position(String code,String name) {
        var z=BigDecimal.ZERO; var o=BigDecimal.ONE;
        return new SimulationTypes.Position(code,name,o,z,o,o,o,z,z,z,z,z,z,z,z,o,z,o,LocalDate.of(2026,9,10),null);
    }
    FactItem item(String key,String verdict,Map<String,Object> facts) {
        return new FactItem(key,verdict,key+" 证据正文。","test-source",LocalDate.of(2026,9,10),facts);
    }
    Map<String,Object> managers(String... names) {
        var list=new ArrayList<Map<String,Object>>();
        for(String name:names) list.add(Map.of("manager_name",name,"begin_date","2020-01-02"));
        return Map.of("current_managers",list,"latest_change_date","2020-01-02","latest_ann_date","2020-01-03");
    }
    /** 七项全部VALID且带完整事实字段的标准事实；各测试按需替换单项。 */
    Facts standardFacts(LocalDate asOf) {
        return new Facts("006730",asOf,"VALID","HOLDING_DIAGNOSIS_FACTS_V1",List.of(
                item("MANAGER","VALID",managers("张三")),
                item("SCALE","VALID",Map.of("latest_share","1000000000","latest_trade_date","2026-09-10","previous_share","990000000")),
                item("SAME_TYPE_RANK","VALID",Map.of("rank",10,"comparable_count",40,"percentile","0.250000","scope","CURRENT_MARKET_ACTIVE_TUSHARE_PRO_FUND")),
                item("BENCHMARK","VALID",Map.of("benchmark_code","000300","return_diff_60d","0.012000")),
                item("DRAWDOWN","VALID",Map.of("history_days",600,"current_drawdown","-0.050000","max_drawdown","-0.200000")),
                item("FEE","VALID",Map.of("management_fee","1.5","custodian_fee","0.25")),
                item("DIVIDEND","VALID",Map.of("total_events",1,"latest_ann_date","2026-01-10","latest_cash_dividend","0.05"))));
    }
    Facts replacing(Facts base,FactItem replacement) {
        var items=new ArrayList<FactItem>(base.items());
        items.replaceAll(i->i.item().equals(replacement.item()) ? replacement : i);
        return new Facts(base.fundCode(),base.asOfDate(),base.overall(),base.basis(),items);
    }
    UUID archive(Facts facts,Instant when) { return service.archive(user,position("006730","诊断测试基金"),facts,when); }
    FactItem judged(UUID id,String key) {
        return repo.detail(user,"006730",id).items().stream().filter(i->i.item().equals(key)).findFirst().orElseThrow();
    }
    @Test void first_report_without_baseline_is_honestly_insufficient() {
        UUID id=archive(standardFacts(LocalDate.of(2026,9,10)),day1);
        var detail=repo.detail(user,"006730",id);
        assertEquals("INSUFFICIENT",detail.report().verdict());
        assertEquals(LocalDate.of(2026,9,10),detail.report().cutoffDate());
        for(String key:List.of("MANAGER","SCALE","FEE","DIVIDEND")) {
            var item=judged(id,key);
            assertEquals("INSUFFICIENT",item.verdict());
            assertTrue(item.evidence().contains("无基线"),key+" 应注明无基线");
        }
        // 事实自足的项目首份报告即可判定，不伪造为数据不足。
        assertEquals("VALID",judged(id,"BENCHMARK").verdict());
        assertEquals("VALID",judged(id,"DRAWDOWN").verdict());
        assertEquals("VALID",judged(id,"SAME_TYPE_RANK").verdict());
        assertEquals(7,detail.items().size());
    }
    @Test void second_report_compares_with_baseline_and_marks_changes() {
        archive(standardFacts(LocalDate.of(2026,9,10)),day1);
        var next=standardFacts(LocalDate.of(2026,9,14));
        next=replacing(next,item("MANAGER","VALID",managers("李四")));
        next=replacing(next,item("SCALE","VALID",Map.of("latest_share","2500000000","latest_trade_date","2026-09-14")));
        next=replacing(next,item("FEE","VALID",Map.of("management_fee","1.8","custodian_fee","0.25")));
        next=replacing(next,item("DIVIDEND","VALID",Map.of("total_events",2,"latest_ann_date","2026-09-01","latest_cash_dividend","0.03")));
        UUID id=archive(next,day1.plusSeconds(3*86400));
        var detail=repo.detail(user,"006730",id);
        assertEquals("CHANGED",detail.report().verdict());
        assertEquals("CHANGED",judged(id,"MANAGER").verdict());
        assertTrue(judged(id,"MANAGER").evidence().contains("经理变更本身不等于管理能力恶化"));
        assertEquals("CHANGED",judged(id,"SCALE").verdict());
        assertEquals("CHANGED",judged(id,"FEE").verdict());
        assertEquals("CHANGED",judged(id,"DIVIDEND").verdict());
        assertTrue(judged(id,"DIVIDEND").evidence().contains("现金分红再投"));
        // 与上一份基线一致的事实全部记成立，总体恢复VALID。
        var steady=replacing(next,item("SCALE","VALID",Map.of("latest_share","2600000000","latest_trade_date","2026-09-15")));
        UUID stable=archive(steady,day1.plusSeconds(4*86400));
        assertEquals("VALID",judged(stable,"MANAGER").verdict());
        assertEquals("VALID",judged(stable,"FEE").verdict());
        assertEquals("VALID",judged(stable,"DIVIDEND").verdict());
        assertEquals("VALID",judged(stable,"SCALE").verdict());
        assertEquals("VALID",repo.detail(user,"006730",stable).report().verdict());
    }
    @Test void same_type_rank_requires_three_consecutive_days_in_bottom_quartile() {
        var bottom=item("SAME_TYPE_RANK","VALID",Map.of("rank",39,"comparable_count",40,"percentile","0.975000"));
        var insufficient=new FactItem("SAME_TYPE_RANK","INSUFFICIENT","同类样本不足。","same-type-comparison",LocalDate.of(2026,9,10),null);
        UUID first=archive(replacing(standardFacts(LocalDate.of(2026,9,10)),bottom),day1);
        assertEquals("VALID",judged(first,"SAME_TYPE_RANK").verdict());
        assertTrue(judged(first,"SAME_TYPE_RANK").evidence().contains("连续诊断日不足 3 个"));
        UUID second=archive(replacing(standardFacts(LocalDate.of(2026,9,14)),bottom),day1.plusSeconds(3*86400));
        assertEquals("VALID",judged(second,"SAME_TYPE_RANK").verdict());
        UUID third=archive(replacing(standardFacts(LocalDate.of(2026,9,15)),bottom),day1.plusSeconds(4*86400));
        assertEquals("CHANGED",judged(third,"SAME_TYPE_RANK").verdict());
        assertTrue(judged(third,"SAME_TYPE_RANK").evidence().contains("受控样本，不是全市场同类平均"));
        // 数据不足日中断连续统计，重新起算。
        UUID gap=archive(replacing(standardFacts(LocalDate.of(2026,9,16)),insufficient),day1.plusSeconds(5*86400));
        assertEquals("INSUFFICIENT",judged(gap,"SAME_TYPE_RANK").verdict());
        UUID restart=archive(replacing(standardFacts(LocalDate.of(2026,9,17)),bottom),day1.plusSeconds(6*86400));
        assertEquals("VALID",judged(restart,"SAME_TYPE_RANK").verdict());
    }
    @Test void same_day_same_input_is_idempotent_and_changed_input_appends_version() {
        var facts=standardFacts(LocalDate.of(2026,9,10));
        UUID first=archive(facts,day1);
        assertEquals(first,archive(facts,day1.plusSeconds(300)));
        UUID changed=archive(replacing(facts,item("FEE","VALID",Map.of("management_fee","1.6","custodian_fee","0.25"))),day1.plusSeconds(600));
        assertNotEquals(first,changed);
        var history=service.history("006730",1,20);
        assertEquals(2,history.reports().totalCount());
        assertEquals(1,repo.history(user,"006730",1,1).items().size());
        assertThrows(SimulationException.class,()->service.history("006730",0,20));
    }
    @Test void python_failure_records_insufficient_without_fabrication() {
        UUID id=archive(null,day1);
        var detail=repo.detail(user,"006730",id);
        assertEquals("INSUFFICIENT",detail.report().verdict());
        assertNull(detail.report().cutoffDate());
        for(var item:detail.items()) {
            assertEquals("INSUFFICIENT",item.verdict());
            assertTrue(item.evidence().contains("不补造数据"));
            assertNull(item.facts());
        }
    }
    @Test void cross_user_access_is_rejected_and_batch_is_scoped_to_holdings() {
        archive(standardFacts(LocalDate.of(2026,9,10)),day1);
        assertEquals(1,service.latest().size());
        db.sql("INSERT INTO sim_position(user_id,fund_code,snapshot,updated_at) VALUES (:user,'001632',CAST(:data AS jsonb),now())")
                .param("user",user).param("data",positions.encode(position("001632","无诊断基金"))).update();
        // 当前持仓但尚无诊断报告的基金不出现在批量摘要中。
        assertEquals(1,service.latest().size());
        login(createUser(),Set.of(PermissionCode.PORTFOLIO_SELF_READ));
        assertTrue(service.latest().isEmpty());
        assertThrows(SimulationException.class,()->service.history("006730",1,20));
        login(user,Set.of());
        assertThrows(AccessDeniedException.class,()->service.latest());
        assertThrows(AccessDeniedException.class,()->service.history("006730",1,20));
    }
    @Test void database_rejects_rewriting_archived_report() {
        UUID id=archive(standardFacts(LocalDate.of(2026,9,10)),day1);
        assertThrows(org.springframework.dao.DataAccessException.class,
                ()->db.sql("UPDATE holding_diagnosis_report SET verdict='VALID' WHERE report_id=:id").param("id",id).update());
    }
    @Test void content_hash_check_catches_tampered_items() {
        UUID id=archive(standardFacts(LocalDate.of(2026,9,10)),day1);
        db.sql("ALTER TABLE holding_diagnosis_report DISABLE TRIGGER holding_diagnosis_immutable").update();
        db.sql("UPDATE holding_diagnosis_report SET items=CAST('[]' AS jsonb) WHERE report_id=:id").param("id",id).update();
        db.sql("ALTER TABLE holding_diagnosis_report ENABLE TRIGGER holding_diagnosis_immutable").update();
        assertThrows(IllegalStateException.class,()->repo.detail(user,"006730",id));
    }
}
