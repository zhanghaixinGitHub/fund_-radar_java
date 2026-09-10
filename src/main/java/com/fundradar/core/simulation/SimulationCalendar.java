package com.fundradar.core.simulation;

import java.time.*;
import java.util.NavigableSet;
import java.util.TreeSet;
import static com.fundradar.core.simulation.SimulationTypes.*;

/** 显式采用已核验开市日的模拟规则；范围不足即停止，不把普通工作日充作交易日。 */
public final class SimulationCalendar {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final CalendarData data;
    private final NavigableSet<LocalDate> days;
    public SimulationCalendar(CalendarData data) {
        this.data = data;
        this.days = new TreeSet<>(data.sessions());
    }
    public LocalDate today(Instant now) { return now.atZone(ZONE).toLocalDate(); }
    public LocalDate atOrAfter(LocalDate date) {
        if (date.isBefore(data.coverageStart()) || date.isAfter(data.coverageEnd())) throw missing();
        LocalDate result = days.ceiling(date);
        if (result == null) throw missing();
        return result;
    }
    public LocalDate next(LocalDate date) { return atOrAfter(date.plusDays(1)); }
    public boolean isSession(LocalDate date) { return days.contains(date); }
    public LocalDate tradeDate(Instant now) {
        var time = now.atZone(ZONE);
        var date = time.toLocalDate();
        return atOrAfter(time.toLocalTime().isBefore(LocalTime.of(15,0)) ? date : date.plusDays(1));
    }
    public Instant cutoff(LocalDate date) { return date.atTime(15,0).atZone(ZONE).toInstant(); }
    public LocalDate firstPlanBase(LocalDate start, Instant now) {
        var time = now.atZone(ZONE);
        var earliest = time.toLocalTime().isBefore(LocalTime.of(10,0)) ? time.toLocalDate() : time.toLocalDate().plusDays(1);
        return start.isAfter(earliest) ? start : earliest;
    }
    public LocalDate scheduled(String frequency, int day, LocalDate base) {
        return switch (frequency) {
            case "DAILY" -> atOrAfter(base);
            case "WEEKLY" -> base.plusDays(Math.floorMod(day-base.getDayOfWeek().getValue(),7));
            case "MONTHLY" -> {
                var month = YearMonth.from(base);
                var candidate = month.atDay(Math.min(day,month.lengthOfMonth()));
                if (candidate.isBefore(base)) {
                    month = month.plusMonths(1);
                    candidate = month.atDay(Math.min(day,month.lengthOfMonth()));
                }
                yield candidate;
            }
            default -> throw new IllegalArgumentException("定投周期不合法。");
        };
    }
    public LocalDate nextScheduled(String frequency, int day, LocalDate previous) {
        return scheduled(frequency,day,previous.plusDays(1));
    }
    private SimulationException missing() {
        return new SimulationException("CALENDAR_UNAVAILABLE","该日期超出已核验交易日历，需更新日历后继续。");
    }
}
