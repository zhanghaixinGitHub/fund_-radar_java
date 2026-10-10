package com.fundradar.core.direction1d;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.*;
import java.util.*;

/** 综合分析单独验收，不伪装为已训练的两个统计分支；原协议校验保持原样。 */
public final class Direction1dAnalysisPolicy {
    public static final String PROTOCOL="DIRECTION_1D_ANALYSIS_V1";
    private Direction1dAnalysisPolicy() {}
    private static void require(boolean condition,String reason) {
        if(!condition) throw new IllegalArgumentException(reason);
    }
    private static Instant at(JsonNode node,String name) { return Direction1dPolicy.instant(node,name); }
    private static boolean text(JsonNode node,int max) { return node.isTextual()&&!node.asText().isBlank()&&node.asText().length()<=max; }
    public static JsonNode validate(JsonNode p,String code,Instant now) {
        require("002112".equals(code)&&code.equals(p.path("fund_code").asText())
            &&PROTOCOL.equals(p.path("schema_version").asText())&&p.path("horizon_trading_days").asInt()==1
            &&"FORWARD_ORIGINAL".equals(p.path("kind").asText())
            &&"UNIT_NAV_DIRECTION_THREE_STATE_V2".equals(p.path("target_definition").asText())
            &&"EXACT_UNIT_NAV_CHANGE_V1".equals(p.path("direction_policy").asText())
            &&"UNVALIDATED".equals(p.path("validation_status").asText())
            &&p.path("model_released").isBoolean()&&!p.path("model_released").asBoolean()
            &&p.has("up_probability")&&p.get("up_probability").isNull(),"ANALYSIS_PROTOCOL_INVALID");
        LocalDate base=LocalDate.parse(p.path("base_nav_date").asText()),target=LocalDate.parse(p.path("target_nav_date").asText());
        Instant open=at(p,"window_open_at"),deadline=at(p,"deadline_at"),asOf=at(p,"as_of"),generated=at(p,"generated_at");
        require(target.equals(Direction1dCalendar.next(base))&&Direction1dCalendar.VERSION.equals(p.path("calendar_version").asText())
            &&open.equals(base.atTime(15,0).atZone(Direction1dPolicy.ZONE).toInstant())
            &&deadline.equals(target.atTime(15,0).atZone(Direction1dPolicy.ZONE).toInstant())
            &&!asOf.isBefore(open)&&!generated.isBefore(asOf)&&generated.isBefore(deadline)&&!generated.isAfter(now.plusSeconds(5))
            &&at(p,"expires_at").isAfter(generated),"ANALYSIS_WINDOW_INVALID");
        require(p.path("revision_sequence").isIntegralNumber()&&p.path("revision_sequence").asLong()>0
            &&p.path("task_key").asText().equals(PROTOCOL+":002112:"+target+":r"+p.path("revision_sequence").asLong())
            &&p.path("input_identity").asText().matches("[a-f0-9]{64}")
            &&p.path("input_hash").asText().matches("[a-f0-9]{64}")
            &&p.path("input_hash").equals(p.path("snapshot_hash")),"ANALYSIS_IDENTITY_INVALID");
        UUID.fromString(p.path("input_snapshot_id").asText());
        require(text(p.path("cohort_id"),80)&&text(p.path("fund_name"),160),"ANALYSIS_IDENTITY_INVALID");
        JsonNode input=p.path("input"),values=input.path("values");
        require("002112".equals(input.path("fund_code").asText())&&values.isArray()&&values.size()<=61
            &&at(input,"feature_as_of").equals(asOf),"ANALYSIS_INPUT_INVALID");
        UUID.fromString(input.path("source_id").asText());
        LocalDate previous=null;
        for(JsonNode v:values) {
            LocalDate day=LocalDate.parse(v.path("nav_date").asText());
            require(!day.isAfter(base)&&(previous==null||day.isAfter(previous))
                &&new BigDecimal(v.path("unit_nav").asText()).signum()>0
                &&v.path("source_id").equals(input.path("source_id"))&&!at(v,"observed_at").isAfter(asOf),"ANALYSIS_NAV_INVALID");
            previous=day;
        }
        require(values.isEmpty()?p.path("latest_nav_date").isNull():previous.toString().equals(p.path("latest_nav_date").asText()),"ANALYSIS_NAV_DATE_INVALID");
        JsonNode a=p.path("analysis"),facts=p.path("evidence");
        require(Set.of("UP","DOWN").contains(a.path("direction").asText())&&"LOW".equals(a.path("confidence").asText())
            &&text(a.path("summary"),250)&&text(a.path("synthesis"),600)&&facts.isObject()&&!facts.isEmpty(),"ANALYSIS_RESULT_INVALID");
        boolean supports=false;
        for(String kind:List.of("reasons","counterpoints")) {
            JsonNode rows=a.path(kind);
            require(rows.isArray()&&rows.size()<=(kind.equals("reasons")?6:4)&&(!kind.equals("reasons")||!rows.isEmpty()),"ANALYSIS_REASONS_INVALID");
            for(JsonNode r:rows) {
                require(text(r.path("title"),120)&&text(r.path("meaning"),250)&&text(r.path("implication"),250)
                    &&r.path("refs").isArray()&&!r.path("refs").isEmpty()&&r.path("refs").size()<=5
                    &&Set.of("支持上涨","支持下跌","双向影响","背景观察").contains(r.path("role").asText()),"ANALYSIS_REASON_INVALID");
                Set<String> seen=new HashSet<>();
                for(JsonNode ref:r.path("refs")) require(ref.isTextual()&&facts.has(ref.asText())&&seen.add(ref.asText()),"ANALYSIS_REFERENCE_INVALID");
                if(kind.equals("reasons")&&r.path("role").asText().equals(a.path("direction").asText().equals("UP")?"支持上涨":"支持下跌")) supports=true;
            }
        }
        require(supports,"ANALYSIS_DIRECTION_UNEXPLAINED");
        for(String field:List.of("limitations","change_conditions")) {
            JsonNode entries=a.path(field);
            require(entries.isArray()&&entries.size()<=(field.equals("limitations")?5:3)
                &&(!field.equals("limitations")||!entries.isEmpty()),"ANALYSIS_TEXT_INVALID");
            for(JsonNode entry:entries) require(text(entry,250),"ANALYSIS_TEXT_INVALID");
        }
        for(JsonNode fact:facts) {
            // 同一公司多个事项原文合为一份依据，不能截断引文；整包仍受入口 150000 字符限制。
            boolean bundle=text(fact.path("event_group"),100)&&fact.path("sources").isArray();
            require(text(fact.path("text"),bundle?150000:2500),"ANALYSIS_FACT_INVALID");
            for(JsonNode source:factSources(fact)) {
                URI uri=URI.create(source.path("url").asText());
                require(text(source.path("title"),220)&&text(source.path("url"),1500)
                    &&Set.of("http","https").contains(uri.getScheme())&&uri.getHost()!=null&&uri.getUserInfo()==null
                    &&!LocalDate.parse(source.path("publishedDate").asText()).isAfter(asOf.atZone(Direction1dPolicy.ZONE).toLocalDate()),"ANALYSIS_SOURCE_INVALID");
            }
        }
        JsonNode narrative=p.path("narrative");
        require("PREDICTION_INFORMATION_ZH_V4".equals(narrative.path("styleVersion").asText())
            &&narrative.path("summary").equals(a.path("summary"))&&narrative.path("context").equals(a.path("synthesis"))
            &&narrative.path("conditions").equals(a.path("change_conditions"))
            &&narrative.path("supporting").asText("invalid").isEmpty()&&narrative.path("opposing").asText("invalid").isEmpty()
            &&narrative.path("drivers").size()==a.path("reasons").size(),"ANALYSIS_NARRATIVE_MISMATCH");
        validateDrivers(narrative.path("drivers"),a.path("reasons"),facts);
        validateDrivers(narrative.path("counterpoints"),a.path("counterpoints"),facts);
        require(p.path("inventory").isArray()&&p.path("inventory").size()==16
            &&p.path("inventory").equals(narrative.path("inventory")),"ANALYSIS_INVENTORY_INVALID");
        return p;
    }
    /** 新资料保留全部原文来源；旧记录仅有 source 时按原有契约读取，禁止漏掉或替换来源。 */
    private static List<JsonNode> factSources(JsonNode fact) {
        List<JsonNode> result=new ArrayList<>();
        if(fact.has("sources")) {
            JsonNode sources=fact.path("sources");
            require(sources.isArray()&&!sources.isEmpty()&&sources.size()<=100,"ANALYSIS_SOURCE_INVALID");
            Set<JsonNode> seen=new HashSet<>();
            for(JsonNode source:sources) {
                require(source.isObject()&&seen.add(source),"ANALYSIS_SOURCE_INVALID");
                result.add(source);
            }
            require(result.get(0).equals(fact.path("source")),"ANALYSIS_SOURCE_INVALID");
        } else if(fact.path("source").isObject()) result.add(fact.path("source"));
        return result;
    }
    private static void validateDrivers(JsonNode drivers,JsonNode reasons,JsonNode facts) {
        require(drivers.isArray()&&drivers.size()==reasons.size(),"ANALYSIS_NARRATIVE_MISMATCH");
        for(int i=0;i<reasons.size();i++) {
            JsonNode r=reasons.get(i),d=drivers.get(i);List<String> observations=new ArrayList<>();
            List<JsonNode> sources=new ArrayList<>();Set<String> relations=new LinkedHashSet<>();
            for(JsonNode ref:r.path("refs")) {
                JsonNode fact=facts.path(ref.asText());observations.add(fact.path("text").asText());
                sources.addAll(factSources(fact));
                if(!fact.path("relation").asText().isBlank()) relations.add(fact.path("relation").asText());
            }
            require(d.path("sources").isArray()&&d.path("sources").size()==sources.size()
                &&d.path("relation").asText().equals(String.join("；",relations)),"ANALYSIS_NARRATIVE_MISMATCH");
            for(int j=0;j<sources.size();j++) require(d.path("sources").get(j).equals(sources.get(j)),"ANALYSIS_NARRATIVE_MISMATCH");
            require(d.path("observation").asText().equals(String.join("\n",observations))
                &&d.path("category").equals(facts.path(r.path("refs").get(0).asText()).path("category"))
                &&d.path("title").equals(r.path("title"))&&d.path("meaning").equals(r.path("meaning"))
                &&d.path("implication").equals(r.path("implication"))&&d.path("assessment").equals(r.path("role")),"ANALYSIS_NARRATIVE_MISMATCH");
        }
    }
    /** 无基准日净值的预测在答案公布后按原基准日期核对，绝不参与旧模型训练。 */
    public static void validateLabel(ObjectMapper json,JsonNode envelope,JsonNode original,Instant now) throws Exception {
        String raw=envelope.path("payload_json").asText();JsonNode p=envelope.path("payload");
        require(raw.length()<150000&&Direction1dPolicy.hash(raw).equals(envelope.path("content_hash").asText())
            &&json.readTree(raw).equals(p)&&p.path("task_key").equals(original.path("task_key"))
            &&p.path("input_snapshot_id").equals(original.path("input_snapshot_id"))
            &&p.path("target_nav_date").equals(original.path("target_nav_date"))
            &&p.path("target_definition").equals(original.path("target_definition"))
            &&p.path("training_eligible").isBoolean()&&!p.path("training_eligible").asBoolean(),"ANALYSIS_LABEL_INVALID");
        UUID.fromString(envelope.path("snapshot_id").asText());
        require(!at(p,"label_observed_at").isAfter(now.plusSeconds(5))&&!at(p,"label_observed_at").isBefore(at(original,"generated_at")),"ANALYSIS_LABEL_TIME");
        JsonNode a=p.path("base_source"),b=p.path("target_source");
        require(!LocalDate.parse(original.path("target_nav_date").asText()).isAfter(now.atZone(Direction1dPolicy.ZONE).toLocalDate()),"ANALYSIS_LABEL_TIME");
        require(a.path("nav_date").equals(original.path("base_nav_date"))&&b.path("nav_date").equals(original.path("target_nav_date")),"ANALYSIS_LABEL_DATE");
        for(JsonNode v:List.of(a,b)) {
            UUID.fromString(v.path("version_id").asText());
            require(v.path("source_id").equals(original.path("input").path("source_id"))
                &&v.path("content_hash").asText().matches("[a-f0-9]{64}")&&!at(v,"observed_at").isAfter(at(p,"label_observed_at")),"ANALYSIS_LABEL_SOURCE");
        }
        BigDecimal av=new BigDecimal(a.path("unit_nav").asText()),bv=new BigDecimal(b.path("unit_nav").asText());
        require(av.signum()>0&&bv.signum()>0&&av.compareTo(new BigDecimal(p.path("base_unit_nav").asText()))==0
            &&bv.compareTo(new BigDecimal(p.path("target_unit_nav").asText()))==0
            &&p.path("actual_direction").asText().equals(bv.compareTo(av)>0?"UP":bv.compareTo(av)<0?"DOWN":"FLAT")
            &&p.path("y").asInt(-1)==(bv.compareTo(av)>0?1:0)
            &&bv.subtract(av).divide(av,12,RoundingMode.HALF_UP).compareTo(new BigDecimal(p.path("nav_return").asText()))==0,"ANALYSIS_LABEL_VALUE");
    }
}
