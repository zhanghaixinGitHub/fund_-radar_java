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
            if(!PROTOCOL.equals(p.path("protocol").asText()) || p.path("horizon_trading_days").asInt()!=1
                    || !"UNIT_NAV_DIRECTION_V1".equals(p.path("target_definition").asText())
                    || !"FORWARD_ORIGINAL".equals(p.path("kind").asText()) || p.path("model_released").asBoolean(true)
                    || !p.has("up_probability") || !p.get("up_probability").isNull()
                    || !expectedCode.equals(p.path("fund_code").asText())) throw new IllegalArgumentException("PROTOCOL_MISMATCH");
            LocalDate t=LocalDate.parse(p.path("base_nav_date").asText()),u=LocalDate.parse(p.path("target_nav_date").asText());
            Instant open=instant(p,"window_open_at"),deadline=instant(p,"deadline_at"),generated=instant(p,"generated_at");
            if(!open.equals(t.atTime(18,0).atZone(ZONE).toInstant()) || !deadline.equals(u.atTime(8,30).atZone(ZONE).toInstant())
                    || !u.equals(Direction1dCalendar.next(t)) || !p.path("calendar_version").asText().equals(Direction1dCalendar.VERSION)
                    || !p.path("latest_nav_date").asText().equals(t.toString())
                    || generated.isBefore(open) || !generated.isBefore(deadline) || generated.isAfter(now.plusSeconds(5)))
                throw new IllegalArgumentException("INVALID_WINDOW");
            JsonNode input=p.path("input");
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
            Set<String> branches=new HashSet<>(); int available=0;
            String activation=p.path("activation_policy").asText("BEFORE_WINDOW_V1");
            if(!Set.of("BEFORE_WINDOW_V1",ACTIVATION_POLICY).contains(activation))
                throw new IllegalArgumentException("INVALID_ACTIVATION_POLICY");
            for(JsonNode b:p.path("branches")) {
                if(!Set.of("FIXED","WEEKLY").contains(b.path("branch_id").asText()) || !branches.add(b.path("branch_id").asText()))
                    throw new IllegalArgumentException("INVALID_BRANCH");
                if("AVAILABLE".equals(b.path("status").asText())) {
                    double s=b.path("score").asDouble(Double.NaN);
                    if(!Double.isFinite(s)||s<0||s>1 || !b.path("model_hash").asText().matches("[a-f0-9]{64}")
                            || !b.path("predicted_direction").asText().equals(s>.5?"UP":"NON_UP")
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
            String raw=envelope.path("payload_json").asText(); JsonNode p=envelope.path("payload");
            if(raw.length()>150_000 || !hash(raw).equals(envelope.path("content_hash").asText())
                    || !json.readTree(raw).equals(p)) throw new IllegalArgumentException("LABEL_HASH_MISMATCH");
            UUID.fromString(envelope.path("snapshot_id").asText());
            if(!p.path("task_key").equals(original.path("task_key"))
                    || !p.path("input_snapshot_id").equals(original.path("input_snapshot_id"))
                    || !p.path("target_nav_date").equals(original.path("target_nav_date"))
                    || !"UNIT_NAV_DIRECTION_V1".equals(p.path("target_definition").asText())
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
