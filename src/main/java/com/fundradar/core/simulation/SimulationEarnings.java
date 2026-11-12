package com.fundradar.core.simulation;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static com.fundradar.core.simulation.SimulationTypes.*;

/** 收益明细的只读契约和汇总。只使用已保存账务，不在查询时重算交易或补写历史。 */
public final class SimulationEarnings {
    private SimulationEarnings() {}
    /** 本人历史基金目录，含已清仓及等待首笔确认的基金；日期取非撤销订单和收益历史的并集。 */
    public record Fund(String fundCode,String fundName,LocalDate firstDate,LocalDate lastDate,
                       boolean closed,@JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal marketValue,
                       LocalDate navDate,boolean reviewRequired,
                       @com.fasterxml.jackson.annotation.JsonIgnore ValuationState valuationState,
                       @com.fasterxml.jackson.annotation.JsonIgnore LocalDate openingDate) {
        public Fund(String code,String name,LocalDate first,LocalDate last,boolean closed,BigDecimal value,LocalDate nav,boolean review) {
            this(code,name,first,last,closed,value,nav,review,null,null);
        }
    }
    /** 仓储按同一日期聚合；expected 在业务层按各基金首次交易日计算。 */
    public record Aggregate(LocalDate date,int valued,int known,
                            BigDecimal dailyGain,BigDecimal cumulativeGain) {}
    public record Day(LocalDate date,String status,int expectedFunds,int updatedFunds,
                      @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal dailyGain,
                      @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal knownGain,
                      @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal cumulativeGain) {}
    public record Detail(String fundCode,String fundName,String status,
                         @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal dailyGain,
                         @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal cumulativeGain,String message) {
        public Detail(String code,String name,String status,BigDecimal daily,BigDecimal cumulative) {
            this(code,name,status,daily,cumulative,null);
        }
    }
    public record Result(Fund fund,LocalDate startDate,LocalDate endDate,LocalDate firstDate,
                         @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal periodGain,
                         @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal knownGain,
                         boolean complete,int incompleteDays,Page<Day> days,List<Day> curve) {}

    /** 日期覆盖外保持未知，绝不用周一至周五猜测节假日；有效收益记录优先于非交易日提示。 */
    static String missingStatus(LocalDate date,LocalDate last,CalendarData calendar) {
        if(calendar==null || date.isBefore(calendar.coverageStart()) || date.isAfter(calendar.coverageEnd())) return "CALENDAR_UNKNOWN";
        if(!calendar.sessions().contains(date)) return "NON_TRADING";
        return last==null || date.isAfter(last) ? "PENDING" : "MISSING";
    }

    /** 缺少收益不等于缺少净值：只根据实际读取过的行情区分两者。旧记录只给通用等待提示。 */
    static String missingFundStatus(LocalDate date,Fund fund,CalendarData calendar) {
        String fallback=missingStatus(date,fund.lastDate(),calendar);
        if("NON_TRADING".equals(fallback)||"CALENDAR_UNKNOWN".equals(fallback)) return fallback;
        var state=fund.valuationState();
        if(state==null||state.navDates()==null) return fallback;
        if("RETRY".equals(state.status())) return "PENDING";
        if(state.navDates().contains(date.toString())) return "VALUATION_PENDING";
        String latest=state.navDates().stream().max(String::compareTo).orElse(null);
        return latest==null||date.toString().compareTo(latest)>0 ? "NAV_PENDING" : "NAV_MISSING";
    }
    static boolean sourceContains(Fund fund,LocalDate date) {
        var state=fund.valuationState();
        return state==null||state.navDates()==null||state.navDates().contains(date.toString())||date.equals(fund.openingDate());
    }

    /** 每日总额只在所有应有基金均有当日有效收益时发布；knownGain 明确是已知部分，不是完整总收益。 */
    static Result summarize(Fund selected,List<Fund> funds,List<Aggregate> values,LocalDate start,LocalDate end,
                            CalendarData calendar,int page,int size) {
        var byDate=new HashMap<LocalDate,Aggregate>(); values.forEach(v -> byDate.put(v.date(),v));
        var days=new ArrayList<Day>();
        LocalDate first=funds.stream().map(Fund::firstDate).min(LocalDate::compareTo).orElse(null);
        LocalDate actual=first!=null && first.isAfter(start) ? first : start;
        for(LocalDate date=actual; first!=null && !date.isAfter(end); date=date.plusDays(1)) {
            final LocalDate day=date;
            var expected=funds.stream().filter(f -> !f.firstDate().isAfter(day)).toList();
            var v=byDate.get(date);
            int count=expected.size(), known=v==null ? 0 : v.known(), valued=v==null ? 0 : v.valued();
            boolean complete=count>0 && known==count;
            String status;
            if(complete) status="COMPLETE";
            else if(expected.stream().anyMatch(Fund::reviewRequired)) status="REVIEW";
            else if(count>0 && expected.stream().allMatch(f -> day.equals(f.openingDate()))) status="BASELINE";
            else if(v!=null) status=count==1 && known==0 ? "MISSING" : "PARTIAL";
            else {
                var statuses=expected.stream().map(f -> missingFundStatus(day,f,calendar)).toList();
                status=statuses.contains("VALUATION_PENDING") ? "VALUATION_PENDING"
                        : statuses.contains("NAV_MISSING") ? "NAV_MISSING"
                        : !statuses.isEmpty() && statuses.stream().allMatch("NAV_PENDING"::equals) ? "NAV_PENDING"
                        : statuses.stream().findFirst().orElse("PENDING");
            }
            days.add(new Day(date,status,count,known,complete ? v.dailyGain() : null,
                    known>0 ? v.dailyGain() : null,valued==count && v!=null ? v.cumulativeGain() : null));
        }
        // 合计始终取完整匹配区间；分页只影响逐日列表，不能改变合计或曲线。
        int incomplete=(int)days.stream().filter(d -> !Set.of("COMPLETE","NON_TRADING","BASELINE").contains(d.status())).count();
        BigDecimal known=days.stream().map(Day::knownGain).filter(Objects::nonNull).reduce(BigDecimal::add).orElse(null);
        boolean complete=incomplete==0 && known!=null;
        var descending=new ArrayList<>(days); Collections.reverse(descending);
        int offset=Math.min((page-1)*size,descending.size());
        return new Result(selected,start,end,first,complete ? known : null,known,complete,incomplete,
                new Page<>(descending.subList(offset,Math.min(offset+size,descending.size())),page,size,descending.size()),days);
    }
}
