package com.fundradar.core.advice;

import com.fundradar.core.auth.*;
import com.fundradar.core.auth.service.AccessDeniedException;
import com.fundradar.core.simulation.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import static com.fundradar.core.advice.RuleTypes.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实PostgreSQL隔离事务中验证草案幂等、±20%微调边界、确认/取代/撤销生命周期与跨用户拒绝；Python来源用替身。 */
@SpringBootTest(properties={"simulation.enabled=false","portfolio.advice.enabled=false"})
@Transactional
class RuleIntegrationTests {
    @Autowired RuleService service;
    @Autowired RuleRepository repo;
    @Autowired SimulationRepository positions;
    @Autowired JdbcClient db;
    @MockitoBean DraftStatsClient client;
    UUID user;
    @BeforeEach void setup() {
        user=createUser(); login(user,Set.of(PermissionCode.PORTFOLIO_SELF_READ,PermissionCode.SIM_PORTFOLIO_SELF_WRITE));
        positions.lock(user);
        db.sql("INSERT INTO sim_position(user_id,fund_code,snapshot,updated_at) VALUES (:user,'006730',CAST(:data AS jsonb),now())")
                .param("user",user).param("data",positions.encode(position())).update();
    }
    @AfterEach void clear() { CurrentUserContext.clear(); reset(client); }
    UUID createUser() {
        UUID id=UUID.randomUUID();
        db.sql("INSERT INTO user_account(user_id,mobile,display_name,password_hash,role,status) VALUES(:id,:mobile,'规则集成测试','test-only-hash','FUND_USER','ACTIVE')")
                .param("id",id).param("mobile","139"+String.format("%08d",ThreadLocalRandom.current().nextInt(100000000))).update();
        return id;
    }
    void login(UUID id,Set<PermissionCode> permissions) {
        CurrentUserContext.set(new AuthenticatedUser(id,"test","规则测试",AccountRole.FUND_USER,permissions));
    }
    SimulationTypes.Position position() {
        var z=BigDecimal.ZERO; var o=BigDecimal.ONE;
        return new SimulationTypes.Position("006730","规则测试基金",o,z,o,o,o,z,z,z,z,z,z,z,z,o,z,o,LocalDate.of(2026,9,10),null);
    }
    TriggerStats trigger() { return new TriggerStats(843,new BigDecimal("0.0210"),new BigDecimal("15"),12); }
    DraftTier tier(String name,String reduce,String profit) {
        return new DraftTier(name,new BigDecimal(reduce),new BigDecimal(profit),trigger(),trigger());
    }
    DraftStats available(LocalDate cutoff,String marker) {
        return new DraftStats("006730","AVAILABLE",null,cutoff,1765,1703,"ACCUMULATED",
                Map.of("marker",marker,"volatility","0.2100"),
                List.of(tier("CONSERVATIVE","-0.0710","0.0661"),tier("BALANCED","-0.1040","0.0980"),tier("LOOSE","-0.1520","0.1410")),
                "沿用 nav_daily 现有口径，分红不自行还原复权。","HOLDING_RULE_DRAFT_STATS_V1");
    }
    long draftCount() {
        return db.sql("SELECT count(*) FROM holding_rule_draft WHERE user_id=:user").param("user",user).query(Long.class).single();
    }
    long audits(String action) {
        return db.sql("SELECT count(*) FROM audit_log WHERE actor=:actor AND action=:action")
                .param("actor",user.toString()).param("action",action).query(Long.class).single();
    }
    @Test void draft_generation_is_idempotent_by_fingerprint_and_appends_on_change() {
        when(client.read("006730")).thenReturn(available(LocalDate.of(2026,9,10),"a"));
        var first=service.generate("006730");
        assertEquals("AVAILABLE",first.status());
        assertEquals(3,first.tiers().size());
        assertNotNull(first.assumption());
        var again=service.generate("006730");
        assertEquals(first.draftId(),again.draftId());
        assertEquals(1,draftCount());
        // 净值水位推进（统计截止日变化）指纹变化，追加新草案且不覆盖旧的。
        when(client.read("006730")).thenReturn(available(LocalDate.of(2026,9,11),"a"));
        var refreshed=service.generate("006730");
        assertNotEquals(first.draftId(),refreshed.draftId());
        assertEquals(2,draftCount());
        // 调度路径共用同一留档逻辑：相同统计不重复生成，失败结果保留旧草案。
        service.refreshDraft(user,"006730",available(LocalDate.of(2026,9,11),"a"),Instant.now());
        assertEquals(2,draftCount());
        service.refreshDraft(user,"006730",available(LocalDate.of(2026,9,14),"b"),Instant.now());
        assertEquals(3,draftCount());
        service.refreshDraft(user,"006730",new DraftStats("006730","DATA_INSUFFICIENT","历史太短",null,100,0,"ACCUMULATED",null,List.of(),"","HOLDING_RULE_DRAFT_STATS_V1"),Instant.now());
        assertEquals(3,draftCount());
        assertEquals(LocalDate.of(2026,9,14),service.draft("006730").statsCutoffDate());
    }
    @Test void read_path_generates_first_draft_and_insufficient_gives_reason_without_numbers() {
        when(client.read("006730")).thenReturn(available(LocalDate.of(2026,9,10),"a"));
        var view=service.draft("006730");
        assertEquals("AVAILABLE",view.status());
        assertNotNull(view.draftId());
        verify(client,times(1)).read("006730");
        // 数据不足：返回明确状态与原因，不给阈值数字，也不归档草案。
        db.sql("DELETE FROM holding_rule_draft WHERE user_id=:user").param("user",user).update();
        when(client.read("006730")).thenReturn(new DraftStats("006730","DATA_INSUFFICIENT","历史净值不足500个交易日。",
                null,320,0,"ACCUMULATED",null,List.of(),"沿用 nav_daily 现有口径。","HOLDING_RULE_DRAFT_STATS_V1"));
        var lacking=service.draft("006730");
        assertEquals("DATA_INSUFFICIENT",lacking.status());
        assertTrue(lacking.reason().contains("500"));
        assertTrue(lacking.tiers().isEmpty());
        assertNull(lacking.draftId());
        assertEquals(0,draftCount());
        // Python不可用：降级为数据不足而不是伪造空结论。
        when(client.read("006730")).thenThrow(new RuntimeException("connection refused"));
        assertEquals("DATA_INSUFFICIENT",service.generate("006730").status());
        assertEquals(0,draftCount());
    }
    @Test void confirm_uses_draft_values_is_idempotent_and_audited() {
        when(client.read("006730")).thenReturn(available(LocalDate.of(2026,9,10),"a"));
        service.generate("006730");
        var confirmed=service.confirm("006730",new ConfirmRequest("BALANCED",null,null));
        var rule=confirmed.active();
        assertEquals("BALANCED",rule.tier());
        assertEquals(0,new BigDecimal("0.0980").compareTo(rule.takeProfitPct()));
        assertEquals(0,new BigDecimal("-0.1040").compareTo(rule.reduceDrawdownPct()));
        assertEquals(VERSION,rule.ruleVersion());
        assertEquals("ACTIVE",rule.status());
        assertEquals(1,audits("holding-rule-confirm"));
        // 同一参数重复确认不生成新版本、不重复审计。
        var repeated=service.confirm("006730",new ConfirmRequest("BALANCED",null,null));
        assertEquals(rule.ruleId(),repeated.active().ruleId());
        assertEquals(1,service.rules("006730").history().size());
        assertEquals(1,audits("holding-rule-confirm"));
        assertThrows(IllegalArgumentException.class,()->service.confirm("006730",new ConfirmRequest(null,null,null)));
        assertThrows(IllegalArgumentException.class,()->service.confirm("006730",new ConfirmRequest("AGGRESSIVE",null,null)));
    }
    @Test void custom_adjustment_is_limited_to_twenty_percent_of_draft_quantile() {
        when(client.read("006730")).thenReturn(available(LocalDate.of(2026,9,10),"a"));
        service.generate("006730");
        // 恰好+20%与-20%边界允许，记CUSTOM。
        var custom=service.confirm("006730",new ConfirmRequest("BALANCED",new BigDecimal("0.1176"),new BigDecimal("-0.1248")));
        assertEquals("CUSTOM",custom.active().tier());
        assertEquals(Boolean.TRUE,custom.active().ruleParams().get("adjusted"));
        assertEquals("BALANCED",custom.active().ruleParams().get("sourceTier"));
        // 超出±20%拒绝，不产生新规则。
        assertThrows(IllegalArgumentException.class,()->service.confirm("006730",new ConfirmRequest("BALANCED",new BigDecimal("0.1177"),null)));
        assertThrows(IllegalArgumentException.class,()->service.confirm("006730",new ConfirmRequest("BALANCED",null,new BigDecimal("-0.0831"))));
        assertThrows(IllegalArgumentException.class,()->service.confirm("006730",new ConfirmRequest("BALANCED",new BigDecimal("-0.05"),null)));
        assertEquals(1,service.rules("006730").history().size());
    }
    @Test void new_confirmation_supersedes_old_and_revoke_keeps_history() {
        when(client.read("006730")).thenReturn(available(LocalDate.of(2026,9,10),"a"));
        service.generate("006730");
        var first=service.confirm("006730",new ConfirmRequest("CONSERVATIVE",null,null)).active();
        var second=service.confirm("006730",new ConfirmRequest("LOOSE",null,null)).active();
        var rules=service.rules("006730");
        assertEquals(second.ruleId(),rules.active().ruleId());
        assertEquals(2,rules.history().size());
        var old=rules.history().stream().filter(r->r.ruleId().equals(first.ruleId())).findFirst().orElseThrow();
        assertEquals("REVOKED",old.status());
        assertNotNull(old.supersededAt());
        var revoked=service.revoke("006730");
        assertNull(revoked.active());
        var gone=service.rules("006730").history().stream().filter(r->r.ruleId().equals(second.ruleId())).findFirst().orElseThrow();
        assertEquals("REVOKED",gone.status());
        assertNull(gone.supersededAt());
        assertEquals(1,audits("holding-rule-revoke"));
        // 重复撤销幂等，不再留痕。
        assertNull(service.revoke("006730").active());
        assertEquals(1,audits("holding-rule-revoke"));
    }
    @Test void confirm_requires_existing_draft() {
        when(client.read("006730")).thenReturn(new DraftStats("006730","NOT_APPLICABLE","货币基金不适用回撤/止盈类规则。",
                null,0,0,null,null,List.of(),"","HOLDING_RULE_DRAFT_STATS_V1"));
        assertEquals("NOT_APPLICABLE",service.draft("006730").status());
        assertThrows(SimulationException.class,()->service.confirm("006730",new ConfirmRequest("BALANCED",null,null)));
    }
    @Test void cross_user_access_is_rejected() {
        when(client.read("006730")).thenReturn(available(LocalDate.of(2026,9,10),"a"));
        service.generate("006730");
        service.confirm("006730",new ConfirmRequest("BALANCED",null,null));
        login(createUser(),Set.of(PermissionCode.PORTFOLIO_SELF_READ,PermissionCode.SIM_PORTFOLIO_SELF_WRITE));
        assertThrows(SimulationException.class,()->service.draft("006730"));
        assertThrows(SimulationException.class,()->service.generate("006730"));
        assertThrows(SimulationException.class,()->service.rules("006730"));
        assertThrows(SimulationException.class,()->service.confirm("006730",new ConfirmRequest("BALANCED",null,null)));
        assertThrows(SimulationException.class,()->service.revoke("006730"));
        login(user,Set.of(PermissionCode.PORTFOLIO_SELF_READ));
        assertThrows(AccessDeniedException.class,()->service.generate("006730"));
        assertThrows(AccessDeniedException.class,()->service.confirm("006730",new ConfirmRequest("BALANCED",null,null)));
        assertThrows(AccessDeniedException.class,()->service.revoke("006730"));
    }
    @Test void draft_table_rejects_rewriting() {
        when(client.read("006730")).thenReturn(available(LocalDate.of(2026,9,10),"a"));
        var draft=service.generate("006730");
        assertThrows(org.springframework.dao.DataAccessException.class,
                ()->db.sql("UPDATE holding_rule_draft SET tiers=CAST('[]' AS jsonb) WHERE draft_id=:id").param("id",draft.draftId()).update());
    }
}
