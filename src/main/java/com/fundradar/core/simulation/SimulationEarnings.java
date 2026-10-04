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
                       LocalDate navDate,boolean reviewRequired) {}
    /** 仓储按同一日期聚合；expected 在业务层按各基金首次交易日计算。 */
    public record Aggregate(LocalDate date,int valued,int known,
                            BigDecimal dailyGain,BigDecimal cumulativeGain) {}
    public record Day(LocalDate date,String status,int expectedFunds,int updatedFunds,
                      @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal dailyGain,
                      @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal knownGain,
                      @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal cumulativeGain) {}
    public record Detail(String fundCode,String fundName,String status,
                         @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal dailyGain,
                         @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal cumulativeGain) {}
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
            else if(v!=null) status=count==1 && known==0 ? "MISSING" : "PARTIAL";
            else {
                LocalDate last=expected.stream().map(Fund::lastDate).filter(Objects::nonNull).max(LocalDate::compareTo).orElse(null);
                status=missingStatus(date,last,calendar);
            }
            days.add(new Day(date,status,count,known,complete ? v.dailyGain() : null,
                    known>0 ? v.dailyGain() : null,valued==count && v!=null ? v.cumulativeGain() : null));
        }
        // 合计始终取完整匹配区间；分页只影响逐日列表，不能改变合计或曲线。
        int incomplete=(int)days.stream().filter(d -> !Set.of("COMPLETE","NON_TRADING").contains(d.status())).count();
        BigDecimal known=days.stream().map(Day::knownGain).filter(Objects::nonNull).reduce(BigDecimal::add).orElse(null);
        boolean complete=incomplete==0 && known!=null;
        var descending=new ArrayList<>(days); Collections.reverse(descending);
        int offset=Math.min((page-1)*size,descending.size());
        return new Result(selected,start,end,first,complete ? known : null,known,complete,incomplete,
                new Page<>(descending.subList(offset,Math.min(offset+size,descending.size())),page,size,descending.size()),days);
    }
}
