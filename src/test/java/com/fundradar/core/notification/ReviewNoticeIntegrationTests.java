package com.fundradar.core.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fundradar.core.auth.*;
import com.fundradar.core.advice.RuleService;
import com.fundradar.core.advice.RuleTypes.ConfirmRequest;
import com.fundradar.core.integration.ai.*;
import com.fundradar.core.simulation.SimulationRepository;
import com.fundradar.core.simulation.SimulationTypes.Position;
import java.math.BigDecimal;
import com.fundradar.core.fund.api.FundNewsFactsResponse;
import com.fundradar.core.notification.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import static org.junit.jupiter.api.Assertions.*;
import static com.fundradar.core.notification.service.ReviewNoticeService.*;

/** 隔离 schema 的状态与归属测试；合成账户和消息逐例回滚，不写用户实际提醒。 */
@SpringBootTest(properties={"direction1d.initial-delay=P365D","prediction.multi.initial-delay=P365D",
        "direction1d.training-enabled=false","direction1d.review-enabled=false","notification.review.enabled=false",
        "simulation.enabled=false","portfolio.advice.enabled=false"})
@Transactional
class ReviewNoticeIntegrationTests {
    @Autowired ReviewNoticeService service;
    @Autowired NotificationService notifications;
    @Autowired ReviewNoticeChecker checker;
    @Autowired RuleReminderChecker reminders;
    @Autowired RuleService holdingRules;
    @Autowired SimulationRepository positions;
    @Autowired JdbcClient db;
    UUID user,other;
    @BeforeEach void setup() { user=user();other=user();login(user); }
    @AfterEach void clear() { CurrentUserContext.clear(); }
    UUID user() {
        UUID id=UUID.randomUUID();
        db.sql("INSERT INTO user_account(user_id,mobile,display_name,password_hash,role,status) VALUES(:id,:m,'复查隔离测试','synthetic-only','FUND_USER','ACTIVE')")
                .param("id",id).param("m","139"+String.format("%08d",ThreadLocalRandom.current().nextInt(100000000))).update();return id;
    }
    void login(UUID id) { CurrentUserContext.set(new AuthenticatedUser(id,"test","测试",AccountRole.FUND_USER,
            Set.of(PermissionCode.NOTIFICATION_SELF_READ,PermissionCode.NOTIFICATION_SELF_WRITE,
                    PermissionCode.PORTFOLIO_SELF_READ,PermissionCode.SIM_PORTFOLIO_SELF_WRITE))); }
    Observation input(State state,String fact) { return new Observation("account:CONFIRMED:USE_DATE","CONFIRMED",null,"USE_DATE",state,Map.of("title","测试事项","condition",fact),null); }
    @Test void repeatSameFactDoesNotCreateVersionOrUnread() {
        assertTrue(service.observe(user,input(State.ACTIVE,"原条件")));
        var first=service.read(1).items().get(0);service.markRead(first.noticeId(),1);
        assertFalse(service.observe(user,input(State.ACTIVE,"原条件")));
        var result=service.read(1).items().get(0);
        assertTrue(result.read());assertEquals(1,result.revision());assertEquals(1,service.history(result.noticeId(),1).items().size());
    }
    @Test void changedEvidenceKeepsHistoryAndOldPageCannotReadNewVersion() {
        service.observe(user,input(State.ACTIVE,"原条件"));var first=service.read(1).items().get(0);
        service.observe(user,input(State.ACTIVE,"更正条件"));
        var result=service.markRead(first.noticeId(),1);
        assertEquals(2,result.revision());assertFalse(result.read());
        var history=service.history(first.noticeId(),1).items();assertEquals(2,history.size());
        assertEquals("原条件",history.get(1).payload().get("condition"));
    }
    @Test void readIsIndependentFromResolvedRetractedAndExpired() {
        for(State state:State.values()) service.observe(user,input(state,"原条件"));
        var result=service.read(1).items().get(0);assertEquals(State.EXPIRED,result.lifecycle());
        assertEquals(4,result.revision());assertTrue(service.markRead(result.noticeId(),4).read());
        assertEquals(State.EXPIRED,service.read(1).items().get(0).lifecycle());
    }
    @Test void noInitialResolvedNoticeAndNoCrossAccountRead() {
        assertFalse(service.observe(user,input(State.RESOLVED,"尚未到期")));
        service.observe(user,input(State.ACTIVE,"原条件"));var first=service.read(1).items().get(0);
        login(other);assertTrue(service.read(1).items().isEmpty());
        assertThrows(NotificationNotFoundException.class,()->service.history(first.noticeId(),1));
        assertThrows(NotificationNotFoundException.class,()->service.markRead(first.noticeId(),1));
        service.observe(other,input(State.ACTIVE,"另一账号"));assertEquals(1,service.read(1).items().size());
    }

    @Test void todayUsesShanghaiSendDateAndHistoryKeepsPreviousDay() {
        UUID rule = rule(true);
        db.sql("""
                INSERT INTO notification(notification_id,rule_id,deduplication_key,status,payload,created_at)
                SELECT gen_random_uuid(), :rule, gen_random_uuid()::text, 'UNREAD', '{}'::jsonb,
                       (date_trunc('day', CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Shanghai') AT TIME ZONE 'Asia/Shanghai') + delta
                FROM (VALUES (INTERVAL '-1 second'), (INTERVAL '0 second'), (INTERVAL '1 day')) AS offsets(delta)
                """).param("rule", rule).update();

        var today = notifications.matrix("messages", "", false, 1, 10);
        assertEquals(1, today.totalCount());
        assertEquals(1, today.cells().get(0).messageCount());
        assertEquals(1, notifications.feed("events", "002112", false, 1, 10, false, true).totalCount());
        assertEquals(3, notifications.feed("events", "002112", false, 1, 10, true, false).totalCount());
    }

    @Test void feedCombinesSourcesAndFiltersBeforePaging() {
        UUID ruleId = rule(true);
        UUID notificationId = UUID.randomUUID();
        db.sql("""
                INSERT INTO notification(notification_id,rule_id,deduplication_key,status,payload,created_at)
                VALUES(:id,:rule,:key,'READ','{}',CURRENT_TIMESTAMP - INTERVAL '1 day')
                """).param("id", notificationId).param("rule", ruleId).param("key", notificationId.toString()).update();
        service.observe(user, input(State.ACTIVE, "原条件"));

        var first = notifications.feed("messages", "", false, 1, 1);
        assertEquals(2, first.totalCount());
        assertEquals("holding-checks", first.items().get(0).category());
        assertEquals(notificationId, notifications.feed("messages", "", false, 2, 1).items().get(0).id());
        assertEquals(notificationId, notifications.feed("events", "002112", false, 1, 1).items().get(0).id());
        assertEquals(0, notifications.feed("events", "", true, 1, 1).totalCount());
        assertEquals(0, notifications.feed("messages", "005284", false, 1, 1).totalCount());

        login(other);
        assertEquals(0, notifications.feed("messages", "", false, 1, 1).totalCount());
    }
    @Test void requiresAuthAndWritePermission() {
        service.observe(user,input(State.ACTIVE,"条件"));var first=service.read(1).items().get(0);
        CurrentUserContext.clear();assertThrows(RuntimeException.class,()->service.read(1));
        CurrentUserContext.set(new AuthenticatedUser(user,"test","测试",AccountRole.FUND_USER,Set.of(PermissionCode.NOTIFICATION_SELF_READ)));
        assertThrows(RuntimeException.class,()->service.markRead(first.noticeId(),1));
    }
    @Test void historyIsPaginatedAndDatabaseRejectsMutation() {
        for(int n=0;n<22;n++) service.observe(user,input(State.ACTIVE,"条件"+n));
        var first=service.read(1).items().get(0);var page=service.history(first.noticeId(),1);
        assertEquals(20,page.items().size());assertTrue(page.hasMore());
        assertEquals(2,service.history(first.noticeId(),2).items().size());
        assertThrows(RuntimeException.class,()->db.sql("UPDATE review_notice_revision SET lifecycle='RESOLVED' WHERE notice_id=:id").param("id",first.noticeId()).update());
    }
    UUID rule(boolean enabled) {
        db.sql("INSERT INTO watchlist_item(watchlist_item_id,user_id,fund_code) VALUES(:id,:user,'002112') ON CONFLICT DO NOTHING")
                .param("id",UUID.randomUUID()).param("user",user).update();
        return db.sql("UPDATE alert_rule SET enabled=:enabled WHERE user_id=:user AND fund_code='002112' AND rule_type='EVENT' RETURNING rule_id")
                .param("user",user).param("enabled",enabled).query(UUID.class).single();
    }
    FundNewsFactsResponse news(String hash,String received) {
        return new FundNewsFactsResponse("002112","2026-09-29T08:00:00Z",true,
                List.of(new FundNewsFactsResponse.Item("a".repeat(64),"测试基金披露","德邦基金官网","https://www.dbfund.com.cn/example.pdf",
                        "2026-08-31",received,"已披露文件","已披露该文件","基金身份相符",hash)),List.of());
    }
    @Test void onlyEnabledSubscriptionReceivesFactsAndReceiptRereadIsNotMaterialChange() {
        var facts=news("b".repeat(64),"2026-09-28T08:00:00Z");
        assertEquals(0,checker.check(user,facts,LocalDate.of(2026,9,29)));
        rule(true);assertEquals(1,checker.check(user,facts,LocalDate.of(2026,9,29)));
        assertEquals(0,checker.check(user,news("b".repeat(64),"2026-09-29T08:00:00Z"),LocalDate.of(2026,9,29)));
        assertEquals(1,checker.check(user,news("c".repeat(64),"2026-09-29T08:00:00Z"),LocalDate.of(2026,9,29)));
        assertEquals(2,service.read(1).items().get(0).revision());
    }
    @Test void missingSourceIsNotRetractionButKnownWindowCanExpire() {
        rule(true);checker.check(user,news("b".repeat(64),"2026-09-28T08:00:00Z"),LocalDate.of(2026,9,29));
        assertEquals(0,checker.check(user,null,LocalDate.of(2026,9,30)));
        assertEquals(State.ACTIVE,service.read(1).items().get(0).lifecycle());
        assertEquals(1,checker.check(user,null,LocalDate.of(2026,10,1)));
        assertEquals(State.EXPIRED,service.read(1).items().get(0).lifecycle());
        assertEquals(0,checker.check(user,null,LocalDate.of(2026,10,2)));
    }
    @Test void disabledSubscriptionRetractsNoticeAndReenableRestoresWithHistory() {
        UUID id=rule(true);var facts=news("b".repeat(64),"2026-09-28T08:00:00Z");var today=LocalDate.of(2026,9,29);
        checker.check(user,facts,today);
        db.sql("UPDATE alert_rule SET enabled=false WHERE rule_id=:id").param("id",id).update();checker.check(user,null,today);
        assertEquals(State.RETRACTED,service.read(1).items().get(0).lifecycle());
        db.sql("UPDATE alert_rule SET enabled=true WHERE rule_id=:id").param("id",id).update();checker.check(user,facts,today);
        var current=service.read(1).items().get(0);assertEquals(State.ACTIVE,current.lifecycle());assertEquals(3,current.revision());
    }

    @Test void unfollowStopsNewNoticesAndPreservesSubscriptionPreference() {
        UUID id=rule(true);var facts=news("b".repeat(64),"2026-09-28T08:00:00Z");var today=LocalDate.of(2026,9,29);
        checker.check(user,facts,today);
        db.sql("DELETE FROM watchlist_item WHERE user_id=:user AND fund_code='002112'").param("user",user).update();
        checker.check(user,facts,today);
        assertEquals(State.RETRACTED,service.read(1).items().get(0).lifecycle());
        assertTrue(db.sql("SELECT enabled FROM alert_rule WHERE rule_id=:id").param("id",id).query(Boolean.class).single());
        assertEquals(0,checker.check(user,news("c".repeat(64),"2026-09-29T08:00:00Z"),today));
        assertEquals(1,service.read(1).items().size());
    }

    @Test void holdingCrossingsReachThresholdCategoryAndRemainReadAcrossChecks() {
        var day = LocalDate.of(2026, 11, 17);
        savePosition(day, "0.09");
        holdingRules.confirm("006730", new ConfirmRequest("CUSTOM", new BigDecimal("0.10"),
                new BigDecimal("-0.05"), "HOLDING_LOSS"));
        reminders.check(user, day, code -> null, req -> null, code -> null);
        assertEquals(0, count("thresholds"));

        savePosition(day.plusDays(1), "0.10");
        reminders.check(user, day.plusDays(1), code -> null, req -> null, code -> null);
        var notice = notifications.feed("thresholds", "006730", true, 1, 10).items().get(0);
        assertEquals("TAKE_PROFIT", notice.kind());
        service.markRead(notice.id(), notice.revision());
        savePosition(day.plusDays(2), "0.15");
        reminders.check(user, day.plusDays(2), code -> null, req -> null, code -> null);
        reminders.check(user, day.plusDays(2), code -> null, req -> null, code -> null);
        assertEquals(1, count("thresholds"));
        assertEquals(0, notifications.feed("thresholds", "", true, 1, 10).totalCount());

        savePosition(day.plusDays(3), "-0.05");
        reminders.check(user, day.plusDays(3), code -> null, req -> null, code -> null);
        assertEquals("HOLDING_LOSS", notifications.feed("thresholds", "", true, 1, 10).items().get(0).kind());
        savePosition(day.plusDays(4), "0.11");
        reminders.check(user, day.plusDays(4), code -> null, req -> null, code -> null);
        assertEquals(3, count("thresholds"));
        assertEquals(3, count("messages"));
        assertEquals(0, count("signals"));
        login(other);
        assertEquals(0, count("thresholds"));
    }

    @Test void drawdownRequiresVerifiedLatestNavAndDoesNotRepeat() {
        var day = LocalDate.of(2026, 11, 19);
        savePosition(day, "0");
        holdingRules.confirm("006730", new ConfirmRequest("CUSTOM", null, new BigDecimal("-0.10"), "DRAWDOWN"));
        var points = new ArrayList<AiFundNavPoint>();
        for (int i = 62; i >= 1; i--) points.add(new AiFundNavPoint(day.minusDays(i), BigDecimal.ONE, null));
        reminders.check(user, day, code -> null, req -> new AiFundNavHistory(req.code(), points), code -> null);
        assertEquals(0, count("thresholds"));
        points.add(new AiFundNavPoint(day, new BigDecimal("0.90"), null));
        for (int n = 0; n < 2; n++) {
            reminders.check(user, day, code -> null, req -> new AiFundNavHistory(req.code(), points), code -> null);
        }
        assertEquals(1, count("thresholds"));
        assertEquals("DRAWDOWN", notifications.feed("thresholds", "", true, 1, 10).items().get(0).kind());
    }

    @Test void followedFundUsesSignalAndEventCategoriesWithoutDuplicateDelivery() {
        rule(true);
        db.sql("""
                INSERT INTO alert_rule (rule_id,user_id,fund_code,rule_type,threshold,enabled)
                VALUES(:id,:user,'002112','RISK_LEVEL',0.5,true)
                """).param("id", UUID.randomUUID()).param("user", user).update();
        db.sql("UPDATE alert_rule SET updated_at='2026-09-01T00:00:00Z' WHERE user_id=:user")
                .param("user", user).update();
        var day = LocalDate.of(2026, 9, 29);
        reminders.check(user, day, code -> signal(day.minusDays(1), "UP", "LOW"), req -> null, code -> null);
        assertEquals(0, count("signals"));
        var updated = signal(day, "DOWN", "MEDIUM");
        for (int n = 0; n < 2; n++) {
            reminders.check(user, day, code -> updated, req -> null, code -> null);
            checker.check(user, news("b".repeat(64), "2026-09-28T08:00:00Z"), day);
        }
        var signalItems = notifications.feed("signals", "002112", true, 1, 10);
        assertEquals(2, signalItems.totalCount());
        assertEquals(Set.of("SIGNAL_CHANGE", "RISK_LEVEL"),
                new HashSet<>(signalItems.items().stream().map(NotificationService.FeedItem::kind).toList()));
        assertEquals(1, count("events"));
        assertEquals(3, count("messages"));
        db.sql("UPDATE alert_rule SET enabled=false WHERE user_id=:user AND rule_type='SIGNAL_CHANGE'")
                .param("user", user).update();
        reminders.check(user, day.plusDays(1), code -> signal(day.plusDays(1), "UP", "HIGH"), req -> null, code -> null);
        assertEquals(2, count("signals"));
    }

    @Test void currentOneDayAnalysisTriggersOnlyOnDirectionChange() {
        rule(true);
        var day = LocalDate.of(2026, 11, 18);
        reminders.check(user, day, code -> null, req -> null, code -> review(day, "UP", "DATED_ANALYSIS"));
        assertEquals(0, count("signals"));
        var next = day.plusDays(1);
        reminders.check(user, next, code -> null, req -> null, code -> review(next, "DOWN", "RETROSPECTIVE"));
        assertEquals(0, count("signals"));
        reminders.check(user, next, code -> null, req -> null, code -> review(next, "DOWN", "DATED_ANALYSIS"));
        reminders.check(user, next, code -> null, req -> null, code -> review(next, "DOWN", "DATED_ANALYSIS"));
        assertEquals(1, count("signals"));
        assertEquals("走势判断变化", notifications.feed("signals", "", true, 1, 10)
                .items().get(0).payload().get("title"));
    }

    private JsonNode review(LocalDate day, String direction, String kind) {
        return new ObjectMapper().valueToTree(Map.of("fundCode", "002112",
                "adoptedAt", day.atStartOfDay().toInstant(ZoneOffset.UTC).toString(),
                "periods", List.of(Map.of("horizonId", "T1", "kind", kind,
                        "startDate", day.toString(), "decision", direction))));
    }

    private AiSignalSummary signal(LocalDate day, String direction, String risk) {
        return new AiSignalSummary(UUID.randomUUID(), "002112", day, "SCORED", direction, null,
                null, risk, null, null, null, null, null, day.atStartOfDay().toInstant(ZoneOffset.UTC));
    }

    private long count(String category) {
        return notifications.feed(category, "", false, 1, 10).totalCount();
    }

    private void savePosition(LocalDate day, String gain) {
        positions.lock(user);
        var z = BigDecimal.ZERO;
        var o = BigDecimal.ONE;
        var position = new Position("006730", "提醒测试基金", o, z, o, o, o, z, new BigDecimal(gain),
                z, z, z, z, z, z, o, z, o, day, null);
        db.sql("""
                INSERT INTO sim_position(user_id,fund_code,snapshot,updated_at) VALUES(:user,'006730',CAST(:data AS jsonb),now())
                ON CONFLICT(user_id,fund_code) DO UPDATE SET snapshot=EXCLUDED.snapshot
                """).param("user", user).param("data", positions.encode(position)).update();
    }
}
