package com.fundradar.core.notification;

import com.fundradar.core.auth.*;
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
    @Autowired ReviewNoticeChecker checker;
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
            Set.of(PermissionCode.NOTIFICATION_SELF_READ,PermissionCode.NOTIFICATION_SELF_WRITE))); }
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
        assertEquals("原条件",history.get(1).payload().get("condition").asText());
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
}
