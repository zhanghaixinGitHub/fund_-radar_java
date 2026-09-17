package com.fundradar.core.advice;

import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import static com.fundradar.core.advice.DiagnosisTypes.*;

/** 逐项终判：以本库上一份报告基线为准，Python结论仅作参考；首份报告无基线项如实记数据不足。 */
@Component
public class DiagnosisPolicy {
    private static final BigDecimal BOTTOM_QUARTILE=new BigDecimal("0.75");
    /** 来源失败时七项全部记数据不足并写明原因，不补造任何事实。 */
    public List<FactItem> failureItems() {
        var items=new ArrayList<FactItem>(ITEM_KEYS.size());
        for(String key:ITEM_KEYS)
            items.add(new FactItem(key,"INSUFFICIENT",
                    "诊断事实来源暂时不可用（diagnosis-facts 接口失败），本基金当日诊断记为数据不足，不补造数据。",
                    "diagnosis-facts",null,null));
        return items;
    }
    public List<FactItem> judge(Facts facts,List<FactItem> baseline,List<RankDay> rankDays) {
        var base=new HashMap<String,FactItem>();
        if(baseline!=null) for(var item:baseline) base.put(item.item(),item);
        var judged=new ArrayList<FactItem>(facts.items().size());
        for(var item:facts.items()) {
            if("INSUFFICIENT".equals(item.verdict())) { judged.add(item); continue; }
            judged.add(switch(item.item()) {
                case "MANAGER" -> manager(item,base.get("MANAGER"));
                case "SCALE" -> scale(item,base.get("SCALE"));
                case "SAME_TYPE_RANK" -> rank(item,rankDays);
                case "FEE" -> fee(item,base.get("FEE"));
                case "DIVIDEND" -> dividend(item,base.get("DIVIDEND"));
                // 基准60日窗口与回撤按事实自足判定，Python结论即终判。
                default -> item;
            });
        }
        return judged;
    }
    public String overall(List<FactItem> items) {
        boolean changed=false,insufficient=false;
        for(var item:items) {
            changed|="CHANGED".equals(item.verdict());
            insufficient|="INSUFFICIENT".equals(item.verdict());
        }
        return changed ? "CHANGED" : insufficient ? "INSUFFICIENT" : "VALID";
    }
    private static FactItem with(FactItem item,String verdict,String note) {
        return new FactItem(item.item(),verdict,item.evidence()+note,item.source(),item.dataAsOfDate(),item.facts());
    }
    private static FactItem noBaseline(FactItem item) {
        return with(item,"INSUFFICIENT","首份诊断报告无基线，如实记为数据不足，不把首次状态伪造成成立。");
    }
    private static BigDecimal decimal(Map<String,Object> facts,String key) {
        if(facts==null || facts.get(key)==null) return null;
        try { return new BigDecimal(String.valueOf(facts.get(key))); }
        catch(NumberFormatException error) { return null; }
    }
    private static String text(Map<String,Object> facts,String key) {
        return facts==null || facts.get(key)==null ? null : String.valueOf(facts.get(key));
    }
    private static TreeSet<String> managers(Map<String,Object> facts) {
        var result=new TreeSet<String>();
        if(facts!=null && facts.get("current_managers") instanceof List<?> list)
            for(Object entry:list) if(entry instanceof Map<?,?> manager)
                result.add(manager.get("manager_name")+"|"+manager.get("begin_date"));
        return result;
    }
    private FactItem manager(FactItem item,FactItem baseline) {
        var current=managers(item.facts());
        if(baseline==null || managers(baseline.facts()).isEmpty() || current.isEmpty()) return noBaseline(item);
        if(managers(baseline.facts()).equals(current)) return with(item,"VALID","当前在任经理组合与上一份报告基线一致。");
        return with(item,"CHANGED","当前在任经理组合与上一份报告基线不一致；经理变更本身不等于管理能力恶化。");
    }
    private FactItem scale(FactItem item,FactItem baseline) {
        BigDecimal latest=decimal(item.facts(),"latest_share");
        BigDecimal base=baseline==null ? null : decimal(baseline.facts(),"latest_share");
        if(latest==null || base==null || base.signum()<=0) return noBaseline(item);
        BigDecimal ratio=latest.divide(base,10,RoundingMode.HALF_UP).subtract(BigDecimal.ONE);
        if(ratio.compareTo(BigDecimal.ONE)>=0)
            return with(item,"CHANGED","最新规模相对上一份报告基线变化 "+ratio.setScale(4,RoundingMode.HALF_UP).movePointRight(2).stripTrailingZeros().toPlainString()+"%，达到 +100% 暴涨阈值。");
        if(ratio.compareTo(new BigDecimal("-0.5"))<=0)
            return with(item,"CHANGED","最新规模相对上一份报告基线变化 "+ratio.setScale(4,RoundingMode.HALF_UP).movePointRight(2).stripTrailingZeros().toPlainString()+"%，达到 −50% 腰斩阈值。");
        return with(item,"VALID","最新规模相对上一份报告基线变化未达到阈值（+100% / −50%）。");
    }
    private FactItem rank(FactItem item,List<RankDay> days) {
        BigDecimal percentile=decimal(item.facts(),"percentile");
        if(percentile==null) return with(item,"INSUFFICIENT","同类排名分位缺失，不补造数值。");
        if(percentile.compareTo(BOTTOM_QUARTILE)<=0)
            return with(item,"VALID","近一月排名未处于同类受控样本后 25%。");
        int streak=1;
        if(days!=null) for(var day:days) {
            if(!Boolean.TRUE.equals(day.bottom())) break;
            streak++;
        }
        if(streak>=3)
            return with(item,"CHANGED","连续 "+streak+" 个诊断日处于同类受控样本后 25%（受控样本，不是全市场同类平均）。");
        return with(item,"VALID","当日处于同类受控样本后 25%，但连续诊断日不足 3 个（当前连续 "+streak+" 个），暂不记为已改变。");
    }
    private FactItem fee(FactItem item,FactItem baseline) {
        BigDecimal management=decimal(item.facts(),"management_fee"),custodian=decimal(item.facts(),"custodian_fee");
        BigDecimal baseManagement=baseline==null ? null : decimal(baseline.facts(),"management_fee");
        BigDecimal baseCustodian=baseline==null ? null : decimal(baseline.facts(),"custodian_fee");
        if(management==null || custodian==null || baseManagement==null || baseCustodian==null) return noBaseline(item);
        if(management.compareTo(baseManagement)==0 && custodian.compareTo(baseCustodian)==0)
            return with(item,"VALID","管理费率与托管费率与上一份报告基线一致。");
        return with(item,"CHANGED","费用与上一份报告基线不同（基线管理费 "+baseManagement.stripTrailingZeros().toPlainString()
                +"、托管费 "+baseCustodian.stripTrailingZeros().toPlainString()+"；当前管理费 "+management.stripTrailingZeros().toPlainString()
                +"、托管费 "+custodian.stripTrailingZeros().toPlainString()+"）。");
    }
    private FactItem dividend(FactItem item,FactItem baseline) {
        BigDecimal events=decimal(item.facts(),"total_events");
        BigDecimal baseEvents=baseline==null ? null : decimal(baseline.facts(),"total_events");
        if(events==null || baseEvents==null) return noBaseline(item);
        String latest=text(item.facts(),"latest_ann_date"),baseLatest=text(baseline.facts(),"latest_ann_date");
        boolean added=events.compareTo(baseEvents)>0
                || (latest!=null && baseLatest!=null && latest.compareTo(baseLatest)>0)
                || (latest!=null && baseLatest==null);
        if(!added) return with(item,"VALID","基线之后无新分红事件。");
        return with(item,"CHANGED","基线之后出现新分红事件（最近公告日 "+(latest==null ? "未知" : latest)
                +"，累计 "+events.stripTrailingZeros().toPlainString()+" 条）；仅提示现金分红再投口径，不解释为恶化。");
    }
}
