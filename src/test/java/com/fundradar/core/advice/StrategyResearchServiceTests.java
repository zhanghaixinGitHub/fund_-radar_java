package com.fundradar.core.advice;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

/** 覆盖用户遇到的“未超过两年却报错”，并守住 Java/Python 共用的回放日期边界。 */
class StrategyResearchServiceTests {
    private final LocalDate today=LocalDate.of(2026,9,23);

    @Test void selectingTodayExplainsTheRealCause() {
        var request=new StrategyResearchService.Request("006730",LocalDate.of(2026,1,23),today);
        var error=assertThrows(IllegalArgumentException.class,()->StrategyResearchService.validateRequest(request,today));
        assertTrue(error.getMessage().contains("结束日期必须早于今天"));
        assertTrue(error.getMessage().contains("2026-09-22"));
        assertFalse(error.getMessage().contains("超过两年"));
    }

    @Test void yesterdayAndExistingHistoricalRangeRemainAllowed() {
        assertDoesNotThrow(()->StrategyResearchService.validateRequest(
                new StrategyResearchService.Request("006730",LocalDate.of(2026,1,23),today.minusDays(1)),today));
        assertDoesNotThrow(()->StrategyResearchService.validateRequest(
                new StrategyResearchService.Request("006730",LocalDate.of(2024,7,1),LocalDate.of(2025,12,31)),today));
    }

    @Test void sizeLimitStillMatchesPythonIncludingLeapYears() {
        LocalDate end=today.minusDays(1);
        assertDoesNotThrow(()->StrategyResearchService.validateRequest(
                new StrategyResearchService.Request("006730",end.minusDays(732),end),today));
        assertTrue(assertThrows(IllegalArgumentException.class,()->StrategyResearchService.validateRequest(
                new StrategyResearchService.Request("006730",end.minusDays(733),end),today)).getMessage().contains("732天"));
    }

    @Test void missingReversedAndFutureDatesHaveSeparateMessages() {
        assertEquals("请选择开始日期和结束日期",assertThrows(IllegalArgumentException.class,()->StrategyResearchService.validateRequest(
                new StrategyResearchService.Request("006730",null,today.minusDays(1)),today)).getMessage());
        for(LocalDate end:new LocalDate[]{today.minusDays(1),today.minusDays(2)})
            assertEquals("开始日期必须早于结束日期",assertThrows(IllegalArgumentException.class,()->StrategyResearchService.validateRequest(
                    new StrategyResearchService.Request("006730",today.minusDays(1),end),today)).getMessage());
        assertTrue(assertThrows(IllegalArgumentException.class,()->StrategyResearchService.validateRequest(
                new StrategyResearchService.Request("006730",today.minusDays(10),today.plusDays(1)),today)).getMessage().contains("结束日期必须早于今天"));
    }

    @Test void fundCodeErrorDoesNotMentionTheDateRange() {
        for(String code:new String[]{null,"","006730,000001","abc123"})
            assertEquals("请输入一只基金的6位代码，例如006730",assertThrows(IllegalArgumentException.class,()->StrategyResearchService.validateRequest(
                    new StrategyResearchService.Request(code,today.minusDays(10),today.minusDays(1)),today)).getMessage());
    }
}
