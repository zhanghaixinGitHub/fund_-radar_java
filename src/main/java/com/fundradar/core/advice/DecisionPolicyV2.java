package com.fundradar.core.advice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;

/** 在线与回放唯一决策组件；权重为实验起点，金额只影响执行而不改变走势信号。 */
@Component
public class DecisionPolicyV2 {
    public static final String VERSION="HOLDING_ADVICE_V2_EXPERIMENTAL";
    private final JsonNode config;
    public DecisionPolicyV2(ObjectMapper json) {
        try(var stream=getClass().getResourceAsStream("/prediction-policy-v1.json")) {
            config=json.readTree(stream).path("strategy");
        } catch(Exception error) { throw new IllegalStateException("综合策略配置无法读取",error); }
    }
    public record Signal(String predictionId,String horizonId,String direction,String modelId,String modelHash,
                         long activationRevision,String dataAsOf) {}
    public record Fact(String title,String content,String source,double factor) {}
    public record PersonalRule(String ruleId,Double takeProfitPercent,Double reduceDrawdownPercent) {}
    public record Input(List<Signal> predictions,Double trendRisk,List<Fact> facts,List<String> missing,
                        boolean held,String preference,boolean defaultPreference,Double holdingGainRate,
                        Double currentDrawdown,PersonalRule personalRule,List<String> constraints) {}
    public record Decision(String generationStatus,String decision,String summary,Double score,
                           List<String> supportingEvidence,List<String> opposingEvidence,List<String> facts,
                           List<String> missingOptionalFactors,String strategyVersion,List<Signal> modelRefs,
                           String preference,boolean defaultPreference,Map<String,Double> effectiveWeights,
                           List<String> executionConstraints,Map<String,Object> error) {}

    public Decision decide(Input input) {
        String preference=input.preference()==null ? "BALANCED" : input.preference();
        JsonNode weights=config.path("preferences").path(preference);
        if(weights.isMissingNode()) throw new IllegalArgumentException("未知策略偏好");
        var support=new ArrayList<String>(); var against=new ArrayList<String>();
        var used=new LinkedHashMap<String,Double>();
        double signal=0,total=0;
        for(var prediction:input.predictions()) {
            double weight=weights.path(prediction.horizonId()).asDouble(0);
            if(weight<=0 || !Set.of("UP","NON_UP").contains(prediction.direction())) continue;
            int direction="UP".equals(prediction.direction()) ? 1 : -1;
            signal+=weight*direction; total+=weight;
            used.put(prediction.horizonId(),weight);
            (direction>0 ? support : against).add(label(prediction.horizonId())+"预测"+(direction>0?"上涨":"下跌或持平")
                    +"，数据截至"+prediction.dataAsOf());
        }
        if(total==0) return failed("PREDICTION_REQUIRED","没有可计算的预测或基础方法，请先生成多周期预测。",input.missing());
        final double normalization=total;
        used.replaceAll((key,value)->value/normalization);
        signal/=total;
        double trend=input.trendRisk()==null ? 0 : clamp(input.trendRisk());
        if(input.trendRisk()!=null) (trend>0?support:against).add("近二十日走势和回撤变化因子="+trend+"（初始实验规则）");
        double factFactor=input.facts().stream().mapToDouble(Fact::factor).average().orElse(0);
        var facts=new ArrayList<String>();
        for(var fact:input.facts()) {
            facts.add(fact.title()+"："+fact.content()+"；来源："+fact.source());
            if(fact.factor()!=0) (fact.factor()>0?support:against).add(fact.title()+"，事件因子="+fact.factor());
        }
        double score=config.path("weights").path("model").asDouble()*signal
                +config.path("weights").path("trend_risk").asDouble()*trend
                +config.path("weights").path("facts_events").asDouble()*clamp(factFactor);
        String decision;
        if(!input.held()) decision=score>0 ? "BUY" : "AVOID";
        else if(score>config.path("add_above").asDouble()) decision="ADD";
        else if(score>0) decision="HOLD";
        else if(score>config.path("sell_at_or_below").asDouble()) decision="REDUCE";
        else decision="SELL";
        // 已确认止盈/回撤规则的单位为百分比；没有个人规则时不虚构风险偏好。
        var rule=input.personalRule();
        if(input.held() && rule!=null) {
            boolean profit=rule.takeProfitPercent()!=null && input.holdingGainRate()!=null
                    && input.holdingGainRate()*100>=rule.takeProfitPercent();
            boolean drawdown=rule.reduceDrawdownPercent()!=null && input.currentDrawdown()!=null
                    && -input.currentDrawdown()*100>=rule.reduceDrawdownPercent();
            if(profit || drawdown) {
                if(!"SELL".equals(decision)) decision="REDUCE";
                against.add("已确认个人规则 "+rule.ruleId()+" 触发"+(profit?"止盈":"回撤减仓")+"；优先于默认实验动作。");
            }
        }
        String summary="综合已接入信息，实验判断为"+actionLabel(decision)+"。";
        return new Decision("SUCCEEDED",decision,summary,score,List.copyOf(support),List.copyOf(against),List.copyOf(facts),
                List.copyOf(input.missing()),VERSION,List.copyOf(input.predictions()),preference,input.defaultPreference(),
                Map.copyOf(used),List.copyOf(input.constraints()),null);
    }
    public Decision failed(String code,String message,List<String> missing) {
        return new Decision("FAILED",null,"本次建议生成失败："+message,null,List.of(),List.of(),List.of(),missing,
                VERSION,List.of(),"BALANCED",true,Map.of(),List.of(),Map.of("code",code,"stage","DECISION_INPUT", "summary",message));
    }
    private static double clamp(double value) { if(!Double.isFinite(value)) throw new IllegalArgumentException("因子必须为有限数"); return Math.max(-1,Math.min(1,value)); }
    public java.math.BigDecimal positionRatio(String action) {
        String key=switch(action) {case "BUY"->"buy_cash_ratio";case "ADD"->"add_cash_ratio";case "REDUCE"->"reduce_share_ratio";default->null;};
        return key==null ? java.math.BigDecimal.ONE : config.path("position_policy").path(key).decimalValue();
    }
    private static String label(String horizon) { return Map.of("T5_V1","五日","T20_V1","二十日","M6_V1","半年").getOrDefault(horizon,horizon); }
    public static String actionLabel(String action) { return Map.of("BUY","买入","AVOID","不建议买入","ADD","加仓","HOLD","继续持有","REDUCE","减仓","SELL","卖出").getOrDefault(action,"生成失败"); }
}
