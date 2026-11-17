package com.fundradar.core.advice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** 当前持仓的分问题判断，不对缺失周期重新分配权重，也不按总分推导买卖。 */
@Component
public class HoldingAdvicePolicy {
    public static final String VERSION="HOLDING_ADVICE_V4_EVIDENCE";
    private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private final ObjectMapper json;
    public HoldingAdvicePolicy(ObjectMapper json) {this.json=json;}

    public ObjectNode decide(String code,JsonNode personal,JsonNode rating,JsonNode review,JsonNode diagnosis,Instant now) {
        var result=json.createObjectNode();
        result.put("strategyVersion",VERSION);result.put("generationStatus","SUCCEEDED");
        result.putNull("decision");result.put("adviceState","INSUFFICIENT");
        result.put("preference",personal.path("preference").asText("BALANCED"));result.put("defaultPreference",!personal.hasNonNull("preference"));
        for(String field:List.of("supportingEvidence","opposingEvidence","neutralEvidence","facts","missingOptionalFactors",
                "executionConstraints","reassessmentConditions","modelRefs","periodAssessments")) result.putArray(field);
        var missing=result.withArray("missingOptionalFactors");var limits=result.withArray("executionConstraints");
        var position=personal.path("position");
        boolean held=number(position,"shares").orElse(BigDecimal.ZERO).signum()>0;
        if(!held) missing.add("当前已无正份额持仓，不新增持仓操作判断。");
        if(!position.path("issue").asText("").isBlank()) missing.add("当前持仓有待核对问题，先核对账本再形成操作判断。");
        LocalDate today=now.atZone(ZONE).toLocalDate();
        LocalDate navDate=date(position.path("navDate").asText());
        if(navDate==null || navDate.isAfter(today)) missing.add("持仓净值日期尚未核实。");
        String ratingDate=rating.path("asOfDate").asText("");
        boolean ratingUsable=code.equals(rating.path("fundCode").asText())
                && Set.of("SCORED","RATED").contains(rating.path("status").asText())
                && number(rating,"score").isPresent() && date(ratingDate)!=null && !date(ratingDate).isAfter(today)
                && future(rating.path("validUntil").asText(),now);
        if(!ratingUsable) missing.add("完整有效评分尚未具备，不能确认基金质量依据；旧期或部分评分仅供参考。");
        else {
            result.withArray("facts").add("评分考察日为"+ratingDate+"，总分"+rating.path("score").asText()+"；分数不直接代表买卖时机。");
            for(var dimension:rating.path("dimensions")) {
                // 正式评分说明含计算方法，日常建议只展示同批次业务结论；原文留在快照。
                String explanation=dimension.path("assessment").path("conclusion").asText("");
                if(!explanation.isBlank()) result.withArray("facts").add(dimension.path("name").asText()+"："+explanation);
            }
        }
        for(var limitation:rating.path("limitations")) result.withArray("neutralEvidence").add(limitation.asText());
        var active=new HashMap<String,JsonNode>();var seen=new HashSet<String>();
        if(!code.equals(review.path("fundCode").asText())) missing.add("尚未取得本基金正式四周期分析。");
        else for(var period:review.path("periods")) {
            String horizon=period.path("horizonId").asText();
            if(!Set.of("T1","T5_V1","T20_V1","M6_V1").contains(horizon)) continue;
            var view=period.deepCopy(); result.withArray("periodAssessments").add(view);
            if(!seen.add(horizon)) {missing.add(label(horizon)+"存在重复结果，需要核对。");active.remove(horizon);continue;}
            String kind=period.path("kind").asText();
            if("CONDITIONAL_OUTLOOK".equals(kind)) {
                result.withArray("neutralEvidence").add("半年仅为有条件展望，不作为确定日期的操作方向。");continue;
            }
            LocalDate start=date(period.path("startDate").asText()),end=date(period.path("endDate").asText());
            boolean timeValid=start!=null && end!=null && !start.isAfter(today) && !end.isBefore(today)
                    && !(horizon.equals("T1") && !now.isBefore(end.atTime(15,0).atZone(ZONE).toInstant()));
            boolean provenance=known(period.path("sourceCutoff").asText(),now) && known(period.path("analysisAt").asText(),now);
            boolean explained=!period.path("primaryReason").asText("").isBlank()
                    && !period.path("counterReason").asText("").isBlank() && !period.path("conditions").isEmpty();
            if(!"DATED_ANALYSIS".equals(kind) || !timeValid || !provenance || !explained
                    || !Set.of("UP","DOWN","FLAT").contains(period.path("decision").asText())) {
                result.withArray("neutralEvidence").add(label(horizon)+"当前不提供可用方向："+
                        (!timeValid || "RETROSPECTIVE".equals(kind)?"区间已结束或尚未开始，仅供参考。":period.path("awaitingReason").asText("资料或结论尚不足。")));
                continue;
            }
            active.put(horizon,period);
            LocalDate base=date(period.path("baseNavDate").asText());
            if((horizon.equals("T5_V1") || horizon.equals("T20_V1")) && (base==null || navDate==null || navDate.isBefore(base)))
                addUnique(result,"missingOptionalFactors","持仓净值尚未覆盖正式分析的基准日，先核对估值。");
            // 同一原文跨周期只展示一次；周期不是独立事件票数，不累加打分。
            String reason=period.path("primaryReason").asText();
            addUnique(result,"DOWN".equals(period.path("decision").asText())?"opposingEvidence":"supportingEvidence",reason);
            addUnique(result,"opposingEvidence",period.path("counterReason").asText());
            for(var condition:period.path("conditions")) addUnique(result,"reassessmentConditions",condition.asText());
        }
        if(!active.containsKey("T5_V1")) missing.add("五日缺少当前有效的明确判断，无法核对近期操作时机。");
        if(!active.containsKey("T20_V1")) missing.add("二十日缺少当前有效的明确判断，无法核对中期持有方向。");
        for(var item:diagnosis.path("items")) {
            if("INSUFFICIENT".equals(item.path("verdict").asText()))
                addUnique(result,"neutralEvidence",diagnosisLabel(item.path("item").asText())+"资料不足，仅保留已有评分和正式分析，不补造结论。");
            else if(!item.path("evidence").asText("").isBlank()) addUnique(result,"facts",
                    diagnosisLabel(item.path("item").asText())+"："+diagnosisText(item)+"（资料截至"+item.path("dataAsOfDate").asText("待核对")+"）");
        }
        boolean pendingBuy=false,pendingSell=false;
        for(var order:personal.path("orders")) {
            pendingBuy|="BUY".equals(order.path("side").asText());pendingSell|="SELL".equals(order.path("side").asText());
        }
        if(pendingBuy) limits.add("已有待确认买入，追加前先一并考虑，待买金额尚不算已持仓。");
        if(pendingSell) limits.add("已有待确认卖出，不重复提出相同卖出安排。");
        boolean plans=!personal.path("plans").isEmpty();
        if(plans) limits.add("已有执行中的定投；本建议不会暂停、修改或额外执行计划。");
        boolean frozen=number(position,"frozenShares").orElse(BigDecimal.ZERO).signum()>0;
        boolean available=number(position,"availableShares").orElse(BigDecimal.ZERO).signum()>0;
        if(frozen) limits.add("存在冻结份额，已计入可卖限制，不重复扣减。");
        limits.add("未核齐本次申赎开放状态及逐笔持有期限费用，不提供精确买卖数量；实际以交易渠道为准。");
        limits.add("缺少完整个人资金与风险承受信息，不据此认定适合继续增加投入。");
        if(personal.hasNonNull("knownWeightPct")) limits.add("本基金占已知模拟持仓市值"+personal.path("knownWeightPct").asText()+"%；未覆盖部分不计为零，也不代表完整行业风险。");
        var qualityParts=new ArrayList<String>();
        if(ratingUsable) for(var dimension:rating.path("dimensions")) {
            String conclusion=dimension.path("assessment").path("conclusion").asText("");
            if(!conclusion.isBlank()) qualityParts.add(dimension.path("name").asText()+"："+conclusion);
        }
        String quality=ratingUsable?(qualityParts.isEmpty()?"已读取有效评分；分项业务解释尚不完整，总分不直接决定买卖。":String.join("；",qualityParts)) :"基金质量依据尚不足。";
        String timing="五日或二十日依据不足，暂不形成操作判断。";
        String action=null,summary="资料尚不足，暂不形成综合建议。";
        if(missing.isEmpty()) {
            String shortDirection=active.get("T5_V1").path("decision").asText();
            String medium=active.get("T20_V1").path("decision").asText();
            boolean longUp=active.containsKey("M6_V1") && "UP".equals(active.get("M6_V1").path("decision").asText());
            if("DOWN".equals(medium) && "DOWN".equals(shortDirection) && !longUp) {
                action="REDUCE";timing="五日与二十日均有下行依据，可考虑降低部分暴露；不能据此推断需要全部退出。";
                summary="考虑减仓，先核对费用与可卖份额。";
            } else {
                action="HOLD";timing="五日与二十日"+(shortDirection.equals(medium)?"方向一致，但长期条件和个人投入条件仍有限制。":"方向不同，暂不以短期变化替代中期持有判断。" );
                summary="继续持有，暂缓加仓；关注主要依据是否变化。";
            }
        }
        var rule=personal.path("rule");boolean ruleTriggered=false;
        var profit=number(rule,"takeProfitPct");var gain=number(position,"holdingGainRate");
        if(profit.isPresent() && gain.isPresent() && gain.get().compareTo(profit.get())>=0) {
            ruleTriggered=true; addUnique(result,"opposingEvidence","已达到本人确认的止盈线，应复核持仓；不自动卖出。");
        }
        var drawdown=number(rule,"reduceDrawdownPct");
        // 回撤必须来自同一正式诊断的明确数值，不能从文字、亏损率或旧预测特征猜测。
        Optional<BigDecimal> currentDrawdown=Optional.empty();
        for(var item:diagnosis.path("items")) if("DRAWDOWN".equals(item.path("item").asText())
                && navDate!=null && navDate.equals(date(item.path("dataAsOfDate").asText()))
                && !"INSUFFICIENT".equals(item.path("verdict").asText())) currentDrawdown=number(item.path("facts"),"current_drawdown");
        if(drawdown.isPresent()) {
            if(currentDrawdown.isEmpty()) limits.add("已确认减仓线，但缺少可核实的当前回撤数值，不能宣称尚未触发。");
            else if(currentDrawdown.get().compareTo(drawdown.get())<=0) {
                ruleTriggered=true;addUnique(result,"opposingEvidence","已达到本人确认的回撤减仓线，应复核持仓；不自动卖出。");
            }
        }
        if(ruleTriggered && held && available && navDate!=null && !navDate.isAfter(today) && position.path("issue").asText("").isBlank()) {
            action="REDUCE";summary="已触及本人确认规则，考虑减仓并先复核操作条件。";
            timing="本次优先提示已确认个人规则；公共分析缺项仍保留，不宣称资料齐全。";
        }
        if("REDUCE".equals(action) && (pendingSell || !available)) {
            action="HOLD";summary="暂缓新增操作，先处理待确认卖出或可卖份额限制。";
            limits.add("此处暂缓操作不代表看好后续走势。");
        }
        if(action!=null) { result.put("decision",action);result.put("adviceState",missing.isEmpty()?"READY":"QUALIFIED"); }
        result.put("summary",summary);result.put("qualityAssessment",quality);result.put("timingAssessment",timing);
        result.put("personalAssessment",pendingBuy || pendingSell || plans || frozen?"已有交易、定投或冻结安排，操作前应一并核对。":"本次未发现本基金待确认订单、执行中定投或冻结份额；不代表已核齐个人资金条件。");
        result.put("dataAsOf",ratingDate);
        result.put("validUntil",today.plusDays(1).atStartOfDay(ZONE).toInstant().toString());
        addUnique(result,"reassessmentConditions","持仓、订单、定投或本人规则变化后重新评估；下一个北京时间自然日不自动沿用本次操作判断。");
        return result;
    }
    private void addUnique(ObjectNode target,String key,String text) {
        if(text.isBlank()) return;
        for(var old:target.withArray(key)) if(old.asText().equals(text)) return;
        target.withArray(key).add(text);
    }
    private static Optional<BigDecimal> number(JsonNode node,String key) {
        try {return node.hasNonNull(key)?Optional.of(new BigDecimal(node.path(key).asText())):Optional.empty();}
        catch(NumberFormatException e) {return Optional.empty();}
    }
    private static String diagnosisText(JsonNode item) {
        var facts=item.path("facts");String kind=item.path("item").asText();
        if("FEE".equals(kind) && facts.hasNonNull("management_fee") && facts.hasNonNull("custodian_fee"))
            return "年管理费率"+facts.path("management_fee").asText()+"%、年托管费率"+facts.path("custodian_fee").asText()+"%；已反映在净值，不能重复扣除；不代表本次申赎费用。";
        if("SAME_TYPE_RANK".equals(kind) && number(facts,"month_change_rate").isPresent())
            return "当前接入的"+facts.path("comparable_count").asText()+"只同类样本中排名"+facts.path("rank").asText()+
                "，近一月涨跌"+number(facts,"month_change_rate").get().multiply(BigDecimal.valueOf(100)).setScale(2,java.math.RoundingMode.HALF_UP)+"%；样本不代表全市场排名。";
        return item.path("evidence").asText().replaceAll("[^。]*Java[^。]*。?","")
            .replaceAll("fund_profile[^。]*。?","").replace("ACCUMULATED 口径","累计净值口径");
    }
    private static LocalDate date(String value) {try{return LocalDate.parse(value);}catch(RuntimeException e){return null;}}
    private static boolean future(String value,Instant now) {try{return OffsetDateTime.parse(value).toInstant().isAfter(now);}catch(RuntimeException e){return false;}}
    private static boolean known(String value,Instant now) {try{return !OffsetDateTime.parse(value).toInstant().isAfter(now);}catch(RuntimeException e){return false;}}
    public static String label(String horizon) {return Map.of("T1","一日","T5_V1","五日","T20_V1","二十日","M6_V1","半年").getOrDefault(horizon,"本周期");}
    private static String diagnosisLabel(String item) {return Map.of("MANAGER","经理任职","SCALE","基金规模","SAME_TYPE_RANK","同类表现","BENCHMARK","基准对照","DRAWDOWN","回撤","FEE","费用","DIVIDEND","分红").getOrDefault(item,"基金诊断");}
}
