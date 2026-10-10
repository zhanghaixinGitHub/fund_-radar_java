package com.fundradar.core.direction1d;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** 跨服务契约校验；Python成功不自动等于Java已提前留档。 */
public final class Direction1dPolicy {
    public static final String PROTOCOL="DIRECTION_1D_V1";
    public static final String ACTIVE_PROTOCOL="DIRECTION_1D_V2";
    public static final String THREE_STATE_TARGET="UNIT_NAV_DIRECTION_THREE_STATE_V2";
    public static final String THREE_STATE_POLICY="EXACT_UNIT_NAV_CHANGE_V1";
    public static final String ACTIVATION_POLICY="AVAILABLE_AT_PREDICTION_V2";
    public static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private Direction1dPolicy() {}
    public static String hash(String raw) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    public static Instant instant(JsonNode n,String key) { return OffsetDateTime.parse(n.path(key).asText()).toInstant(); }
    public static JsonNode validate(ObjectMapper json,String raw,String hash,String expectedCode,Instant now) {
        try {
            if(raw.length()>150_000 || !hash(raw).equals(hash)) throw new IllegalArgumentException("CONTENT_HASH_MISMATCH");
            JsonNode p=json.readTree(raw);
            if(Direction1dAnalysisPolicy.PROTOCOL.equals(p.path("protocol").asText()))
                return Direction1dAnalysisPolicy.validate(p,expectedCode,now);
            if(p.has("revision_sequence") || p.has("input_identity")) {
                if(!p.path("revision_sequence").isIntegralNumber() || !p.path("revision_sequence").canConvertToLong()
                        || p.path("revision_sequence").asLong()<=0
                        || !p.path("input_identity").asText().matches("[a-f0-9]{64}")
                        || !p.path("task_key").asText().endsWith(":r"+p.path("revision_sequence").asLong()))
                    throw new IllegalArgumentException("INVALID_REVISION");
            }
            boolean ternary=ACTIVE_PROTOCOL.equals(p.path("protocol").asText());
            if((!ternary&&!PROTOCOL.equals(p.path("protocol").asText())) || p.path("horizon_trading_days").asInt()!=1
                    || !(ternary?THREE_STATE_TARGET:"UNIT_NAV_DIRECTION_V1").equals(p.path("target_definition").asText())
                    || (ternary&&(!"DIRECTION_1D_EXPERIMENT_V2".equals(p.path("schema_version").asText())
                        || !THREE_STATE_POLICY.equals(p.path("direction_policy").asText())))
                    || !"FORWARD_ORIGINAL".equals(p.path("kind").asText()) || p.path("model_released").asBoolean(true)
                    || !p.has("up_probability") || !p.get("up_probability").isNull()
                    || !expectedCode.equals(p.path("fund_code").asText())) throw new IllegalArgumentException("PROTOCOL_MISMATCH");
            LocalDate t=LocalDate.parse(p.path("base_nav_date").asText()),u=LocalDate.parse(p.path("target_nav_date").asText());
            Instant open=instant(p,"window_open_at"),deadline=instant(p,"deadline_at"),generated=instant(p,"generated_at");
            // 旧原文继续按18:00—08:30核验；新规则以已结束估值日净值为基准，目标日15:00前留档。
            String generation=p.path("generation_policy").asText("LEGACY_WINDOW_V1");
            if(!Set.of("LEGACY_WINDOW_V1","CN_NAV_READY_CLOSE_V1").contains(generation))
                throw new IllegalArgumentException("INVALID_GENERATION_POLICY");
            boolean navReady="CN_NAV_READY_CLOSE_V1".equals(generation);
            if(!open.equals(t.atTime(navReady?15:18,0).atZone(ZONE).toInstant()) || !deadline.equals(u.atTime(navReady?15:8,navReady?0:30).atZone(ZONE).toInstant())
                    || !u.equals(Direction1dCalendar.next(t)) || !p.path("calendar_version").asText().equals(Direction1dCalendar.VERSION)
                    || !p.path("latest_nav_date").asText().equals(t.toString())
                    || generated.isBefore(open) || !generated.isBefore(deadline) || generated.isAfter(now.plusSeconds(5)))
                throw new IllegalArgumentException("INVALID_WINDOW");
            JsonNode input=p.path("input");
            boolean eventEvidence="002112_EVENT_EVIDENCE_V2".equals(input.path("feature_version").asText());
            if(input.path("values").size()!=61 || input.path("features").size()!=7
                    || !input.path("values").get(60).path("nav_date").asText().equals(t.toString())
                    || instant(input,"feature_as_of").isAfter(generated)
                    || instant(input,"max_input_observed_at").isAfter(instant(input,"feature_as_of")))
                throw new IllegalArgumentException("INVALID_INPUT");
            var expectedDays=Direction1dCalendar.inputs(t);int position=0;
            for(JsonNode value:input.path("values")) {
                if(!value.path("nav_date").asText().equals(expectedDays.get(position++).toString())
                        || new java.math.BigDecimal(value.path("unit_nav").asText()).signum()<=0
                        || instant(value,"observed_at").isAfter(instant(input,"feature_as_of"))) throw new IllegalArgumentException("INVALID_INPUT");
            }
            if(!p.path("input_json").isTextual() || (!hash(p.path("input_json").asText()).equals(p.path("input_hash").asText())
                    || !json.readTree(p.path("input_json").asText()).equals(input))) throw new IllegalArgumentException("INPUT_HASH_MISMATCH");
            // 002112综合输入仍包含原7项净值；扩展部分必须明确声明并与同一份原文绑定。
            // 不放宽其他基金的输入，也不把缺新闻误作已取得全量消息。
            if(input.has("information") || "002112_FULL_INFORMATION_V1".equals(input.path("feature_version").asText())) {
                JsonNode information=input.path("information"),numeric=information.path("numeric");
                if(!ternary || !"002112".equals(expectedCode)
                        || !(eventEvidence || "002112_FULL_INFORMATION_V1".equals(input.path("feature_version").asText()))
                        || !input.path("feature_version").equals(information.path("version"))
                        || !numeric.isArray() || numeric.size()!=87 || !information.path("text").isTextual()
                        || information.path("text").asText().length()>25000
                        || !information.path("source_identity").asText().matches("[a-f0-9]{64}")
                        || !information.path("market_date").asText().equals(t.toString())
                        || !information.path("sources").isArray() || information.path("sources").size()>48)
                    throw new IllegalArgumentException("INVALID_INFORMATION_INPUT");
                for(int i=0;i<numeric.size();i++) {
                    JsonNode value=numeric.get(i);
                    if((!value.isNull() && (!value.isNumber() || !Double.isFinite(value.asDouble())))
                            || (i<7 && (!value.isNumber() || value.asDouble()!=input.path("features").get(i).asDouble())))
                        throw new IllegalArgumentException("INVALID_INFORMATION_INPUT");
                }
                for(JsonNode item:information.path("sources")) {
                    if(!Set.of("ANNOUNCEMENT","NEWS","POLICY").contains(item.path("kind").asText())
                            || !item.path("source_hash").asText().matches("[a-f0-9]{64}")
                            || instant(item,"available_at").isAfter(instant(input,"feature_as_of")))
                        throw new IllegalArgumentException("INVALID_INFORMATION_SOURCE");
                }
                if(eventEvidence) {
                    // 拒判也是一条可核验的完整结果，不能因为没有方向就退回旧预测。
                    JsonNode evidence=information.path("event_evidence"),items=evidence.path("events"),values=evidence.path("values");
                    if(!"002112_EVENT_EVIDENCE_V2".equals(evidence.path("version").asText())
                            || !items.isArray() || items.size()>32 || !values.isArray() || values.size()!=4
                            || !evidence.path("overflow").isBoolean()) throw new IllegalArgumentException("INVALID_EVENT_EVIDENCE");
                    double[] totals=new double[4]; double positive=0,negative=0;
                    for(JsonNode event:items) {
                        int index=switch(event.path("field").asText()) {
                            case "announcement_realized" -> 0; case "announcement_forecast" -> 1;
                            case "policy_implemented" -> 2; case "news_realized" -> 3;
                            default -> -1;
                        };
                        double effect=event.path("value").asDouble(Double.NaN),weight=event.path("weight").asDouble(Double.NaN);
                        if(index<0 || !Double.isFinite(effect) || !Double.isFinite(weight) || weight<=0 || weight>1
                                || !event.path("source_hash").asText().matches("[a-f0-9]{64}")
                                || !event.path("report_hash").asText().matches("[a-f0-9]{64}")
                                || event.path("quote").asText().isBlank() || event.path("quote").asText().length()>600
                                || !event.path("code").asText().matches("[0-9]{6}\\.(SH|SZ|BJ)")
                                || !Set.of("ISSUER","PRODUCT").contains(event.path("relation").asText())
                                || !Set.of("BENEFIT","PRESSURE").contains(event.path("direction").asText())
                                || (effect>0)!= "BENEFIT".equals(event.path("direction").asText())
                                || instant(event,"available_at").isAfter(instant(input,"feature_as_of"))
                                || instant(event,"report_available_at").isAfter(instant(input,"feature_as_of"))
                                || LocalDate.parse(event.path("published_date").asText()).isAfter(instant(input,"feature_as_of").atZone(ZONE).toLocalDate()))
                            throw new IllegalArgumentException("INVALID_EVENT_SOURCE");
                        int age=event.path("age_sessions").asInt(-1);
                        if(age<0 || age>=5 || Math.abs(Math.abs(effect)-weight*100*Math.pow(2,-age/2.0))>1e-10)
                            throw new IllegalArgumentException("INVALID_EVENT_WEIGHT");
                        totals[index]+=effect; positive+=Math.max(effect,0); negative+=Math.max(-effect,0);
                    }
                    for(int i=0;i<4;i++) if(!values.get(i).isNumber() || Math.abs(values.get(i).asDouble()-totals[i])>1e-10)
                        throw new IllegalArgumentException("INVALID_EVENT_TOTAL");
                    if(!Double.isFinite(evidence.path("positive").asDouble(Double.NaN))
                            || !Double.isFinite(evidence.path("negative").asDouble(Double.NaN))
                            || Math.abs(evidence.path("positive").asDouble(Double.NaN)-positive)>1e-10
                            || Math.abs(evidence.path("negative").asDouble(Double.NaN)-negative)>1e-10)
                        throw new IllegalArgumentException("INVALID_EVENT_TOTAL");
                }
            }
            Set<String> branches=new HashSet<>(); int available=0;
            String activation=p.path("activation_policy").asText("BEFORE_WINDOW_V1");
            if(!Set.of("BEFORE_WINDOW_V1",ACTIVATION_POLICY).contains(activation))
                throw new IllegalArgumentException("INVALID_ACTIVATION_POLICY");
            for(JsonNode b:p.path("branches")) {
                if(!Set.of("FIXED","WEEKLY").contains(b.path("branch_id").asText()) || !branches.add(b.path("branch_id").asText()))
                    throw new IllegalArgumentException("INVALID_BRANCH");
                boolean abstained="ABSTAINED".equals(b.path("status").asText());
                if(abstained || (eventEvidence && "AVAILABLE".equals(b.path("status").asText()))) {
                    JsonNode decision=b.path("decision"),reasons=decision.path("reason_codes");
                    if(!eventEvidence || !"002112_EVENT_EVIDENCE_V2".equals(decision.path("policy").asText())
                            || !reasons.isArray() || reasons.size()>9 || abstained==reasons.isEmpty())
                        throw new IllegalArgumentException("INVALID_EVENT_DECISION");
                    for(JsonNode reason:reasons) if(!Set.of("NO_DIRECTIONAL_EVENT","EVENT_CONFLICT","MARKET_EVENT_CONFLICT",
                            "WEAK_SIGNAL","EVENT_MODEL_CONFLICT","EVENT_LIMIT","MARKET_INCOMPLETE","MODEL_UNSUPPORTED",
                            "VALIDATION_INSUFFICIENT").contains(reason.asText()))
                        throw new IllegalArgumentException("INVALID_EVENT_REASON");
                    if(abstained) {
                        if(!b.path("score").isNull() || !b.path("predicted_direction").isNull() || !b.path("class_scores").isNull()
                                || !b.path("model_hash").asText().matches("[a-f0-9]{64}")
                                || !instant(b,"trained_at").isBefore(generated)
                                || instant(b,"trained_at").isAfter(instant(b,"registered_at"))
                                || instant(b,"registered_at").isAfter(instant(b,"model_selected_at"))
                                || instant(b,"model_selected_at").isAfter(generated)) throw new IllegalArgumentException("INVALID_ABSTENTION");
                        UUID.fromString(b.path("model_id").asText()); available++;
                    }
                }
                if("AVAILABLE".equals(b.path("status").asText())) {
                    double s=b.path("score").asDouble(Double.NaN);
                    if(eventEvidence && (s<0.6 || input.path("information").path("event_evidence").path("events").isEmpty()
                            || input.path("information").path("event_evidence").path("overflow").asBoolean()))
                        throw new IllegalArgumentException("EVENT_DIRECTION_UNSUPPORTED");
                    if(!Double.isFinite(s)||s<0||s>1 || !b.path("model_hash").asText().matches("[a-f0-9]{64}")
                            || !b.path("predicted_direction").asText().equals(ternary?threeStateWinner(b):s>.5?"UP":"NON_UP")
                            || !instant(b,"trained_at").isBefore(generated)) throw new IllegalArgumentException("INVALID_MODEL");
                    // 新模型可在窗口内真实完成后使用；旧档继续按原策略核验，不篡改旧结论。
                    if(ACTIVATION_POLICY.equals(activation)) {
                        Instant trained=instant(b,"trained_at"),registered=instant(b,"registered_at"),selected=instant(b,"model_selected_at");
                        if(trained.isAfter(registered) || registered.isAfter(selected) || selected.isAfter(generated))
                            throw new IllegalArgumentException("INVALID_MODEL_TIME");
                    } else if(!instant(b,"trained_at").isBefore(open)) throw new IllegalArgumentException("INVALID_MODEL");
                    UUID.fromString(b.path("model_id").asText()); available++;
                } else if(!b.path("score").isNull() || !b.path("predicted_direction").isNull()) throw new IllegalArgumentException("UNAVAILABLE_SCORE");
            }
            if(available==0 || branches.size()!=2) throw new IllegalArgumentException("MODEL_UNAVAILABLE");
            return p;
        } catch(IllegalArgumentException e) { throw e; }
        catch(Exception e) { throw new IllegalArgumentException("INVALID_PAYLOAD",e); }
    }
    public static String receipt(Instant generated,Instant stored,Instant verified,Instant deadline) {
        return generated.isBefore(deadline)&&stored.isBefore(deadline)&&verified.isBefore(deadline)?"VERIFIED":"LATE_ARCHIVE";
    }
    /** 独立验算Python答案原文和十进制标签；修订只能新增，首次口径仍由归档顺序确定。 */
    public static void validateLabel(ObjectMapper json,JsonNode envelope,JsonNode original,Instant now) {
        try {
            if(Direction1dAnalysisPolicy.PROTOCOL.equals(original.path("protocol").asText())) {
                Direction1dAnalysisPolicy.validateLabel(json,envelope,original,now);return;
            }
            String raw=envelope.path("payload_json").asText(); JsonNode p=envelope.path("payload");
            if(raw.length()>150_000 || !hash(raw).equals(envelope.path("content_hash").asText())
                    || !json.readTree(raw).equals(p)) throw new IllegalArgumentException("LABEL_HASH_MISMATCH");
            UUID.fromString(envelope.path("snapshot_id").asText());
            if(!p.path("task_key").equals(original.path("task_key"))
                    || !p.path("input_snapshot_id").equals(original.path("input_snapshot_id"))
                    || !p.path("target_nav_date").equals(original.path("target_nav_date"))
                    || !p.path("target_definition").equals(original.path("target_definition"))
                    || !"FORWARD_ORIGINAL".equals(p.path("kind").asText())
                    || !"AVAILABLE".equals(p.path("status").asText())
                    || LocalDate.parse(p.path("target_nav_date").asText()).isAfter(now.atZone(ZONE).toLocalDate()))
                throw new IllegalArgumentException("LABEL_MISMATCH");
            JsonNode frozen=original.path("input").path("values").get(60),aSource=p.path("base_source"),bSource=p.path("target_source");
            Instant observed=instant(p,"label_observed_at");
            if(observed.isAfter(now.plusSeconds(5)) || observed.isBefore(instant(original,"generated_at"))
                    || !aSource.path("nav_date").equals(original.path("base_nav_date"))
                    || !bSource.path("nav_date").equals(original.path("target_nav_date"))) throw new IllegalArgumentException("INVALID_LABEL_TIME");
            for(JsonNode source:List.of(aSource,bSource)) {
                UUID.fromString(source.path("version_id").asText());
                if(!source.path("source_id").equals(frozen.path("source_id"))
                        || !source.path("content_hash").asText().matches("[a-f0-9]{64}")
                        || instant(source,"observed_at").isAfter(observed)
                        || !instant(source,"expires_at").isAfter(now)) throw new IllegalArgumentException("INVALID_LABEL_SOURCE");
            }
            boolean revised=!aSource.path("content_hash").equals(frozen.path("content_hash"));
            if(!p.path("base_revised").isBoolean() || p.path("base_revised").asBoolean()!=revised
                    || !p.path("training_eligible").isBoolean() || p.path("training_eligible").asBoolean()==revised)
                throw new IllegalArgumentException("INVALID_LABEL_REVISION");
            var a=new java.math.BigDecimal(p.path("base_unit_nav").asText());
            var b=new java.math.BigDecimal(p.path("target_unit_nav").asText());
            if(a.signum()<=0 || b.signum()<=0 || a.compareTo(new java.math.BigDecimal(aSource.path("unit_nav").asText()))!=0
                    || b.compareTo(new java.math.BigDecimal(bSource.path("unit_nav").asText()))!=0
                    || p.path("y").asInt(-1)!=(b.compareTo(a)>0?1:0)
                    || !p.path("actual_direction").asText().equals(b.compareTo(a)>0?"UP":b.compareTo(a)<0?"DOWN":"FLAT")
                    || b.subtract(a).divide(a,12,java.math.RoundingMode.HALF_UP)
                        .compareTo(new java.math.BigDecimal(p.path("nav_return").asText()))!=0)
                throw new IllegalArgumentException("INVALID_LABEL");
        } catch(IllegalArgumentException e){throw e;}
        catch(Exception e){throw new IllegalArgumentException("INVALID_LABEL",e);}
    }
    /** 三类分数只用于核对原文；并列优先持平，再上涨、下跌，不向用户展示为概率。 */
    static String threeStateWinner(JsonNode branch) {
        JsonNode scores=branch.path("class_scores");
        if(!scores.isObject()||scores.size()!=3)throw new IllegalArgumentException("INVALID_CLASS_SCORES");
        String winner=null; double best=-1,total=0;
        for(String label:List.of("FLAT","UP","DOWN")) {
            double value=scores.path(label).asDouble(Double.NaN);
            if(!scores.path(label).isNumber()||!Double.isFinite(value)||value<0||value>1)
                throw new IllegalArgumentException("INVALID_CLASS_SCORES");
            total+=value;if(value>best){best=value;winner=label;}
        }
        if(Math.abs(total-1)>1e-12||Math.abs(best-branch.path("score").asDouble(Double.NaN))>1e-12)
            throw new IllegalArgumentException("INVALID_CLASS_SCORES");
        return winner;
    }
    /** 浏览器camelCase视图；原始snake_case JSON另外原样保存，不参与重新序列化验hash。 */
    public static Object view(JsonNode n) {
        if(n.isObject()) { Map<String,Object> out=new LinkedHashMap<>(); n.fields().forEachRemaining(e->{
            StringBuilder key=new StringBuilder(); boolean upper=false;
            for(char c:e.getKey().toCharArray()) { if(c=='_') upper=true; else { key.append(upper?Character.toUpperCase(c):c); upper=false; } }
            out.put(key.toString(),view(e.getValue())); }); return out; }
        if(n.isArray()) { List<Object> out=new ArrayList<>(); n.forEach(x->out.add(view(x))); return out; }
        if(n.isNull()) return null; if(n.isBoolean()) return n.asBoolean(); if(n.isNumber()) return n.numberValue(); return n.asText();
    }
}
