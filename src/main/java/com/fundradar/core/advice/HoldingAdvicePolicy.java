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
    public static final String PREVIOUS_VERSION="HOLDING_ADVICE_V4_EVIDENCE";
    public static final String VERSION="HOLDING_ADVICE_V5_PERSONAL";
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
        String preference=personal.path("preference").asText("");
        boolean explicitPreference=Set.of("SHORT","BALANCED","LONG").contains(preference);
        if(!explicitPreference) missing.add("尚未确认投资偏好，不把默认均衡当作本人选择。");
        List<String> required=switch(preference) {
            case "SHORT" -> List.of("T5_V1");
            case "LONG" -> List.of("T20_V1","M6_V1");
            default -> List.of("T5_V1","T20_V1");
        };
        for(String horizon:required) if(!active.containsKey(horizon))
            missing.add(label(horizon)+"缺少当前有效的明确判断，尚不能按本人持有偏好作出操作判断。");
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
        limits.add("未记录经核实的可追加现金及全部资金占用，本次不建议新增投入，也不提供买入金额。");
        if(personal.hasNonNull("knownWeightPct")) limits.add("本基金占当前已知持仓市值"+personal.path("knownWeightPct").asText()+"%；未覆盖部分不计为零，也不代表完整行业风险。");
        var qualityParts=new ArrayList<String>();
        if(ratingUsable) for(var dimension:rating.path("dimensions")) {
            String conclusion=dimension.path("assessment").path("conclusion").asText("");
            if(!conclusion.isBlank()) qualityParts.add(dimension.path("name").asText()+"："+conclusion);
        }
        String quality=ratingUsable?(qualityParts.isEmpty()?"已读取有效评分；分项业务解释尚不完整，总分不直接决定买卖。":String.join("；",qualityParts)) :"基金质量依据尚不足。";
        String timing="五日或二十日依据不足，暂不形成操作判断。";
        String action=null,summary="资料尚不足，暂不形成综合建议。";
        if(missing.isEmpty()) {
            String shortDirection=active.containsKey("T5_V1")?active.get("T5_V1").path("decision").asText():"";
            String medium=active.containsKey("T20_V1")?active.get("T20_V1").path("decision").asText():"";
            String longer=active.containsKey("M6_V1")?active.get("M6_V1").path("decision").asText():"";
            boolean reduce=switch(preference) {
                case "SHORT" -> "DOWN".equals(shortDirection);
                case "LONG" -> "DOWN".equals(medium) && "DOWN".equals(longer);
                default -> "DOWN".equals(shortDirection) && "DOWN".equals(medium);
            };
            action=reduce?"REDUCE":"HOLD";
            timing=switch(preference) {
                case "SHORT" -> "按本人短线偏好优先核对五日方向，二十日与半年作为补充，不因短线偏好省略费用与个人限制。";
                case "LONG" -> "按本人长线偏好以二十日和半年有效方向为主，单独五日下跌不触发减仓；长期偏好不覆盖本人资金限制。";
                default -> "按本人均衡偏好同时核对五日与二十日；两者均向下时考虑减仓，方向分歧时暂不追随短期变化。";
            };
            summary=reduce?"考虑减仓，先核对费用与可卖份额。":"继续持有，暂缓加仓；关注主要依据是否变化。";
        }
        // 资金安排是实际决策门槛。未知不按零处理，用款需要不等同于已核实的现金缺口。
        FundingCheck funding=checkFunding(personal,required,active,today,now);
        for(String gap:funding.missing()) addUnique(result,"missingOptionalFactors",gap);
        for(String reason:funding.reasons()) addUnique(result,"opposingEvidence",reason);
        boolean safePosition=held && navDate!=null && !navDate.isAfter(today) && position.path("issue").asText("").isBlank();
        if(funding.lossLimitReached() && safePosition) {
            action="REDUCE";summary="当前持仓亏损或波动承受条件已达到本人限制，考虑减仓并核对费用。";
            timing="本人确认的资金承受条件优先于看涨方向和持有偏好；减仓不能挽回已经发生的亏损。";
        } else if("HOLD".equals(action) && !funding.holdAllowed()) {
            action=null;summary=funding.useDue()?"先核对用款与到账安排，暂缓新增投入；当前不能认定继续持有合适。":"个人资金条件尚不足，暂不形成继续持有或加仓建议。";
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
        if(ruleTriggered && safePosition) {
            action="REDUCE";summary="已触及本人确认规则，考虑减仓并先复核操作条件。";
            timing="本次优先提示已确认个人规则；公共分析缺项仍保留，不宣称资料齐全。";
        }
        if("REDUCE".equals(action) && (pendingSell || !available)) {
            action=null;summary="暂缓新增操作，先处理待确认卖出或可卖份额限制；原减仓依据仍需复核。";
            missing.add("已有卖出安排或没有可卖份额，本次不重复形成卖出建议。");
            limits.add("此处暂缓操作不代表看好后续走势。");
        }
        if(action!=null) { result.put("decision",action);result.put("adviceState",missing.isEmpty()?"READY":"QUALIFIED"); }
        result.put("summary",summary);result.put("qualityAssessment",quality);result.put("timingAssessment",timing);
        result.put("personalAssessment",funding.assessment()+(pendingBuy || pendingSell || plans || frozen?"；已有交易、定投或冻结安排需一并核对。":"。"));
        result.put("dataAsOf",ratingDate);
        result.put("validUntil",today.plusDays(1).atStartOfDay(ZONE).toInstant().toString());
        addUnique(result,"reassessmentConditions","投资偏好、资金安排、持仓、订单、定投或本人规则变化后重新评估；下一个北京时间自然日不自动沿用本次操作判断。");
        return result;
    }
    private record FundingCheck(boolean holdAllowed,boolean lossLimitReached,boolean useDue,
                                List<String> missing,List<String> reasons,String assessment) {}
    private FundingCheck checkFunding(JsonNode personal,List<String> required,Map<String,JsonNode> active,LocalDate today,Instant now) {
        List<String> gaps=new ArrayList<>(),reasons=new ArrayList<>();
        var funding=personal.path("funding");var input=funding.path("input");
        if(!"ACTIVE".equals(funding.path("status").asText()) || !input.isObject()
                || !known(funding.path("confirmedAt").asText(),now))
            return new FundingCheck(false,false,false,List.of("当前没有可用的本人已确认资金安排，不能默认适合继续持有。"),List.of(),"资金安排尚未确认或已撤销，未采用历史安排替代");
        var requiredAmount=number(input,"requiredAmount");var affordable=number(input,"affordableLossAmount");var willing=number(input,"willingLossPct");
        if(requiredAmount.isEmpty() || requiredAmount.get().signum()<0) gaps.add("预计用款金额尚未确认；没有预计用款时请明确填写0。");
        if(affordable.isEmpty() || affordable.get().signum()<0 || willing.isEmpty() || willing.get().signum()<0 || willing.get().compareTo(new BigDecimal("100"))>0)
            gaps.add("愿意接受的损失比例或现实可承担金额尚未确认。");
        boolean complete=personal.path("portfolioComplete").asBoolean(false) && !personal.path("portfolio").isEmpty();
        BigDecimal cost=BigDecimal.ZERO,gain=BigDecimal.ZERO;
        Set<String> dates=new HashSet<>();
        for(var holding:personal.path("portfolio")) {
            var c=number(holding,"cost");var g=number(holding,"holdingGain");LocalDate day=date(holding.path("navDate").asText());
            if(c.isEmpty() || c.get().signum()<0 || g.isEmpty() || day==null || day.isAfter(today) || !holding.path("issue").asText("").isBlank()) complete=false;
            else {cost=cost.add(c.get());gain=gain.add(g.get());dates.add(day.toString());}
        }
        if(!complete || cost.signum()<=0) gaps.add("当前持仓成本、净值日期或亏损信息不完整，不能核对账户承受条件。");
        BigDecimal loss=gain.negate().max(BigDecimal.ZERO);
        boolean reached=false;
        if(complete && cost.signum()>0) {
            boolean amountReached=affordable.isPresent() && affordable.get().signum()>=0 && loss.compareTo(affordable.get())>=0;
            boolean percentReached=willing.isPresent() && willing.get().signum()>=0 && willing.get().compareTo(new BigDecimal("100"))<=0
                    && loss.multiply(new BigDecimal("100")).compareTo(cost.multiply(willing.get()))>=0;
            reached=amountReached || percentReached;
            if(amountReached) reasons.add("当前账户持有亏损"+loss.toPlainString()+"元，已达到本人确认的可承担损失金额"+affordable.get().toPlainString()+"元；减仓不能挽回已发生亏损。");
            if(percentReached) reasons.add("当前账户持有亏损占剩余成本"+loss.multiply(new BigDecimal("100")).divide(cost,2,java.math.RoundingMode.HALF_UP).toPlainString()+"%，已达到本人愿意接受的"+willing.get().toPlainString()+"%；此处不是历史回撤或未来损失预测。");
        }
        boolean useDue=false;
        if(requiredAmount.isPresent() && requiredAmount.get().signum()>0) {
            LocalDate useDate=date(input.path("useDate"));
            if(useDate==null) gaps.add("预计需要用款，但用款日期尚未确认。");
            else {
                LocalDate end=today;
                for(String horizon:required) if(active.containsKey(horizon)) {
                    LocalDate candidate=date(active.get(horizon).path("endDate").asText());
                    if(candidate!=null && candidate.isAfter(end)) end=candidate;
                }
                useDue=!useDate.isAfter(end);
                if(useDue) {gaps.add("用款日已到达或落在本次持有判断区间内，尚未核实现金覆盖及赎回到账日期。");reasons.add("本人预计用款日"+useDate+"，需要"+requiredAmount.get().toPlainString()+"元；用款安排优先，未核实现金覆盖与到账日期前不建议继续持有或追加投入，也不把用款额当成实际现金缺口。");}
            }
        }
        LocalDate confirmed;
        // 确认日期统一按北京时间，不以本机时区或UTC日期触发提前复查。
        try {confirmed=OffsetDateTime.parse(funding.path("confirmedAt").asText()).atZoneSameInstant(ZONE).toLocalDate();} catch(RuntimeException ignored) {confirmed=null;}
        if(input.hasNonNull("reviewIntervalDays") && confirmed!=null) {
            int interval=input.path("reviewIntervalDays").asInt();
            if(interval<1 || !today.isBefore(confirmed.plusDays(interval))) gaps.add("已到本人设定的资金复查日期，请重新确认后再判断继续持有条件。");
        }
        return new FundingCheck(gaps.isEmpty() && !reached,reached,useDue,List.copyOf(gaps),List.copyOf(reasons),
                (reached?"已使用当前持仓账本核对本人损失限制，承受条件已触发":gaps.isEmpty()?"已核对本人用款安排与当前账户持有亏损，尚未达到已确认限制；不保证未来不亏损":"已读取本人资金安排，但继续持有条件尚未核齐")
                +(dates.size()>1?"；各基金净值日期不同，按当前页面各基金最近已公布估值核对，不代表同日历史回撤":""));
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
    private static LocalDate date(JsonNode value) {
        // 已确认资金安排中的LocalDate可能序列化为[年,月,日]；两种真实格式等价，
        // 不能把已有日期误报成用户尚未确认，也不能把畸形或无效日期自动补正。
        if(value.isTextual()) return date(value.asText());
        if(value.isArray() && value.size()==3) {
            for(var part:value) if(!part.isIntegralNumber() || !part.canConvertToInt()) return null;
            try {return LocalDate.of(value.get(0).intValue(),value.get(1).intValue(),value.get(2).intValue());}
            catch(DateTimeException ignored) {return null;}
        }
        return null;
    }
    private static LocalDate date(String value) {try{return LocalDate.parse(value);}catch(RuntimeException e){return null;}}
    private static boolean future(String value,Instant now) {try{return OffsetDateTime.parse(value).toInstant().isAfter(now);}catch(RuntimeException e){return false;}}
    private static boolean known(String value,Instant now) {try{return !OffsetDateTime.parse(value).toInstant().isAfter(now);}catch(RuntimeException e){return false;}}
    public static String label(String horizon) {return Map.of("T1","一日","T5_V1","五日","T20_V1","二十日","M6_V1","半年").getOrDefault(horizon,"本周期");}
    private static String diagnosisLabel(String item) {return Map.of("MANAGER","经理任职","SCALE","基金规模","SAME_TYPE_RANK","同类表现","BENCHMARK","基准对照","DRAWDOWN","回撤","FEE","费用","DIVIDEND","分红").getOrDefault(item,"基金诊断");}
}
