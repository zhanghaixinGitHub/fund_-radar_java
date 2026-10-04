package com.fundradar.core.alert;

import com.fundradar.core.alert.api.UpsertAlertRuleRequest;
import com.fundradar.core.alert.service.AlertRuleService;
import com.fundradar.core.auth.*;
import com.fundradar.core.integration.ai.AiFundClient;
import com.fundradar.core.watchlist.service.WatchlistRequiredException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 合成账户、真实 PostgreSQL 约束及事务回滚，验证关注默认值与本人手动选择的优先级。 */
@SpringBootTest(properties={"direction1d.initial-delay=P365D","prediction.multi.initial-delay=P365D",
        "direction1d.training-enabled=false","direction1d.review-enabled=false","notification.review.enabled=false",
        "simulation.enabled=false","portfolio.advice.enabled=false"})
@Transactional
class AlertSubscriptionIntegrationTests {
    @Autowired JdbcClient db;
    @Autowired AlertRuleService alerts;
    @MockitoBean AiFundClient funds;
    UUID user;

    @BeforeEach void setup() {
        user=createUser();login(user);
    }
    @AfterEach void clear() { CurrentUserContext.clear(); }
    UUID createUser() {
        UUID id=UUID.randomUUID();
        db.sql("INSERT INTO user_account(user_id,mobile,display_name,password_hash,role,status) VALUES(:id,:mobile,'提醒隔离测试','synthetic-only','FUND_USER','ACTIVE')")
                .param("id",id).param("mobile","139"+String.format("%08d",ThreadLocalRandom.current().nextInt(100000000))).update();
        return id;
    }
    void login(UUID id) { CurrentUserContext.set(new AuthenticatedUser(id,"test","测试",AccountRole.FUND_USER,
            Set.of(PermissionCode.ALERT_RULE_SELF_READ,PermissionCode.ALERT_RULE_SELF_WRITE))); }
    void follow(UUID id) {
        follow(id, "002112");
    }
    void follow(UUID id, String code) {
        db.sql("INSERT INTO watchlist_item(watchlist_item_id,user_id,fund_code) VALUES(:id,:user,:code) ON CONFLICT DO NOTHING")
                .param("id",UUID.randomUUID()).param("user",id).param("code",code).update();
    }
    void unfollow() { db.sql("DELETE FROM watchlist_item WHERE user_id=:user").param("user",user).update(); }
    @Test void followCreatesExactlyTwoDefaultsAndAuditsOnlyOnce() {
        follow(user);follow(user);
        var rows=alerts.listCurrentUserRules();
        assertEquals(Set.of("EVENT","SIGNAL_CHANGE"),new HashSet<>(rows.stream().map(r->r.ruleType()).toList()));
        assertTrue(rows.stream().allMatch(r->r.enabled() && r.threshold()==null));
        assertEquals(2L,db.sql("SELECT count(*) FROM audit_log WHERE actor=:user AND action='ALERT_RULE_DEFAULT_CREATED'")
                .param("user",user.toString()).query(Long.class).single());
    }
    @Test void togglesAreIndependentAndRefollowKeepsManualOptOut() {
        follow(user);
        var saved=alerts.upsertCurrentUserRule(new UpsertAlertRuleRequest("002112","EVENT",null,false));
        follow(user);unfollow();
        assertTrue(alerts.listCurrentUserRules().isEmpty());
        assertThrows(WatchlistRequiredException.class,()->alerts.upsertCurrentUserRule(new UpsertAlertRuleRequest("002112","EVENT",null,true)));
        follow(user);
        var rows=alerts.listCurrentUserRules();
        var event=rows.stream().filter(r->r.ruleType().equals("EVENT")).findFirst().orElseThrow();
        assertFalse(event.enabled());assertEquals(saved.ruleId(),event.ruleId());assertEquals(saved.updatedAt(),event.updatedAt());
        assertTrue(rows.stream().filter(r->r.ruleType().equals("SIGNAL_CHANGE")).findFirst().orElseThrow().enabled());
    }
    @Test void existingDisabledRuleWinsOverNewDefaultAndDoesNotLeakBetweenUsers() {
        alerts.upsertCurrentUserRule(new UpsertAlertRuleRequest("002112","EVENT",null,false));
        follow(user);UUID other=createUser();follow(other);login(other);
        assertTrue(alerts.listCurrentUserRules().stream().allMatch(r->r.enabled()));
        alerts.upsertCurrentUserRule(new UpsertAlertRuleRequest("002112","SIGNAL_CHANGE",null,false));
        login(user);
        assertFalse(alerts.listCurrentUserRules().stream().filter(r->r.ruleType().equals("EVENT")).findFirst().orElseThrow().enabled());
        assertTrue(alerts.listCurrentUserRules().stream().filter(r->r.ruleType().equals("SIGNAL_CHANGE")).findFirst().orElseThrow().enabled());
    }

    @Test void canDisableExistingSubscriptionWhenFundDataIsUnavailable() {
        follow(user);
        when(funds.getFund("002112")).thenThrow(new IllegalStateException("synthetic data outage"));
        var row=alerts.upsertCurrentUserRule(new UpsertAlertRuleRequest("002112","EVENT",null,false));
        assertFalse(row.enabled());verifyNoInteractions(funds);
    }

    /** 同时覆盖分页不重不漏、状态筛选、本人隔离，以及保留但已取消关注的规则不进入列表。 */
    @Test void pagesOnlyCurrentUserFollowedRulesAndFiltersEnabledState() {
        for (int index = 1; index <= 6; index++) follow(user, String.format("%06d", index));
        follow(createUser(), "999999");
        follow(user, "888888");
        db.sql("DELETE FROM watchlist_item WHERE user_id=:user AND fund_code='888888'").param("user", user).update();
        alerts.upsertCurrentUserRule(new UpsertAlertRuleRequest("000001", "EVENT", null, false));
        var first = alerts.pageCurrentUserRules(null, 1, 10);
        var second = alerts.pageCurrentUserRules(null, 2, 10);
        assertEquals(12, first.totalCount());assertEquals(2, first.totalPages());
        assertEquals(10, first.items().size());assertEquals(2, second.items().size());
        var ids = new HashSet<UUID>();
        first.items().forEach(row -> assertTrue(ids.add(row.ruleId())));
        second.items().forEach(row -> assertTrue(ids.add(row.ruleId())));
        assertEquals(alerts.listCurrentUserRules().stream().map(row -> row.ruleId()).toList(),
                java.util.stream.Stream.concat(first.items().stream(), second.items().stream()).map(row -> row.ruleId()).toList());
        var enabled = alerts.pageCurrentUserRules(true, 1, 10);
        assertEquals(11, enabled.totalCount());assertTrue(enabled.items().stream().allMatch(row -> row.enabled()));
        var disabled = alerts.pageCurrentUserRules(false, 1, 10);
        assertEquals(1, disabled.totalCount());assertEquals("000001", disabled.items().get(0).fundCode());
        assertFalse(disabled.items().get(0).enabled());
    }

    /** 当前筛选的末页最后一条关闭后回退，全部关闭后返回空的第一页。 */
    @Test void disablingLastItemClampsFilteredPageAndHandlesEmptyList() {
        follow(user);
        var last = alerts.pageCurrentUserRules(true, 2, 1).items().get(0);
        alerts.upsertCurrentUserRule(new UpsertAlertRuleRequest(last.fundCode(), last.ruleType(), null, false));
        var remaining = alerts.pageCurrentUserRules(true, 2, 1);
        assertEquals(1, remaining.page());assertEquals(1, remaining.totalCount());
        var only = remaining.items().get(0);
        alerts.upsertCurrentUserRule(new UpsertAlertRuleRequest(only.fundCode(), only.ruleType(), null, false));
        var empty = alerts.pageCurrentUserRules(true, 2, 1);
        assertEquals(1, empty.page());assertEquals(0, empty.totalPages());assertTrue(empty.items().isEmpty());
        assertEquals(2, alerts.pageCurrentUserRules(false, 1, 10).totalCount());
    }

    @Test void pagingRejectsInvalidBoundsAndAllowsFarPageWithoutOverflow() {
        assertThrows(IllegalArgumentException.class, () -> alerts.pageCurrentUserRules(null, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> alerts.pageCurrentUserRules(null, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> alerts.pageCurrentUserRules(null, 1, 101));
        follow(user);
        assertEquals(1, alerts.pageCurrentUserRules(null, Integer.MAX_VALUE, 100).page());
    }

    /** 模拟升级前已有关注和关闭选择，直接执行真实迁移 SQL；测试事务结束会恢复触发器。 */
    @Test void migrationBackfillsExistingFollowsWithoutChangingOldPreferences() throws Exception {
        db.sql("DROP TRIGGER watchlist_default_alert_rules ON watchlist_item").update();
        db.sql("DROP FUNCTION create_watchlist_default_alert_rules()").update();
        follow(user);
        var old=alerts.upsertCurrentUserRule(new UpsertAlertRuleRequest("002112","EVENT",null,false));
        String sql=new org.springframework.core.io.ClassPathResource("db/migration/V27__default_watchlist_alert_rules.sql")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        db.sql(sql).update();
        var rows=alerts.listCurrentUserRules();assertEquals(2,rows.size());
        var restored=rows.stream().filter(r->r.ruleType().equals("EVENT")).findFirst().orElseThrow();
        assertEquals(old,restored);
        assertTrue(rows.stream().filter(r->r.ruleType().equals("SIGNAL_CHANGE")).findFirst().orElseThrow().enabled());
    }
}
