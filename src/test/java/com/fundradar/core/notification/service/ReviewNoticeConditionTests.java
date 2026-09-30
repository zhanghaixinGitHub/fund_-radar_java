package com.fundradar.core.notification.service;

import com.fundradar.core.portfolio.AccountFundingTypes.*;
import com.fundradar.core.fund.api.FundNewsFactsResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static com.fundradar.core.notification.service.ReviewNoticeService.*;

class ReviewNoticeConditionTests {
    View input(LocalDate due,Integer interval) { return new View(UUID.randomUUID(),Scope.CONFIRMED,1,"ACTIVE",
            new Input(null,due,null,null,null,interval),Instant.parse("2026-09-28T16:30:00Z")); }
    @Test void unknownDateAndLossPreferenceCannotManufactureReminder() {
        assertNull(ReviewNoticeChecker.account(Scope.CONFIRMED,"USE_DATE",input(null,null),LocalDate.of(2026,9,29)));
        assertNull(ReviewNoticeChecker.account(Scope.SIMULATED,"REVIEW_DUE",input(null,null),LocalDate.of(2026,9,29)));
    }
    @Test void reviewUsesChinaConfirmationDateAndFutureConditionIsResolved() {
        var today=LocalDate.of(2026,9,29);
        var later=ReviewNoticeChecker.account(Scope.CONFIRMED,"REVIEW_DUE",input(null,1),today);
        assertEquals(State.RESOLVED,later.lifecycle());assertEquals("2026-09-30",later.payload().get("basisDate"));
        var reached=ReviewNoticeChecker.account(Scope.CONFIRMED,"REVIEW_DUE",input(null,1),today.plusDays(1));
        assertEquals(State.ACTIVE,reached.lifecycle());
    }
    @Test void technicalHashCanDeserializeButCannotReachBrowser() throws Exception {
        ObjectMapper json=new ObjectMapper();
        var item=json.readValue("{\"evidenceHash\":\"abc\",\"title\":\"已披露文件\"}",FundNewsFactsResponse.Item.class);
        assertEquals("abc",item.evidenceHash());assertFalse(json.writeValueAsString(item).contains("evidenceHash"));
    }
    @Test void verifiedCompanyPublicationCanUseExistingSubscriptionWithoutInventingImplementationStage() {
        var item=new FundNewsFactsResponse.Item("a".repeat(64),"公司股权激励草案","巨潮资讯",
                "https://static.cninfo.com.cn/finalpage/2026-09-29/123.PDF","2026-09-29",
                "2026-09-29T18:00:00+08:00","已披露文件","尚待核实是否实施","见6月30日披露持仓","b".repeat(64));
        var observation=ReviewNoticeChecker.publication(UUID.randomUUID(),item,LocalDate.of(2026,9,29));
        assertNotNull(observation);assertEquals("已披露文件",observation.payload().get("stage"));
        var wrong=new FundNewsFactsResponse.Item(item.eventId(),item.title(),item.sourceName(),
                "https://static.cninfo.com.cn.example.com/finalpage/2026-09-29/123.PDF",item.publishedDate(),
                item.firstReceivedAt(),item.stage(),item.summary(),item.relation(),item.evidenceHash());
        assertNull(ReviewNoticeChecker.publication(UUID.randomUUID(),wrong,LocalDate.of(2026,9,29)));
    }
}
