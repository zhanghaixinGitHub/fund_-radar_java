package com.fundradar.core.advice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fundradar.core.auth.CurrentUserContext;
import com.fundradar.core.auth.PermissionCode;
import com.fundradar.core.prediction.MultiPredictionClient;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/** 公共输入在锁外读取；个人状态单语句取快照，保存前短事务重查，只追加建议。 */
@Service
public class HoldingAdviceService {
    private final JdbcClient db;private final ObjectMapper json;private final MultiPredictionClient client;
    private final DiagnosisClient diagnoses;private final HoldingAdvicePolicy policy;private final DecisionServiceV2 legacy;
    private final TransactionTemplate tx;private final Clock clock;
    public HoldingAdviceService(JdbcClient db,ObjectMapper json,MultiPredictionClient client,DiagnosisClient diagnoses,
            HoldingAdvicePolicy policy,DecisionServiceV2 legacy,TransactionTemplate tx,Clock clock) {
        this.db=db;this.json=json;this.client=client;this.diagnoses=diagnoses;this.policy=policy;this.legacy=legacy;this.tx=tx;this.clock=clock;
    }
    private UUID owner(boolean write) {
        var user=CurrentUserContext.requirePermission(PermissionCode.PORTFOLIO_SELF_READ).userId();
        if(write) CurrentUserContext.requirePermission(PermissionCode.SIM_PORTFOLIO_SELF_WRITE);
        return user;
    }
    public JsonNode generate(String code) {
        UUID user=owner(true);legacy.owns(user,code);
        if(!held(personal(user,code))) throw new IllegalArgumentException("当前没有该基金正份额持仓，请查看历史建议。");
        return generateFor(user,code);
    }
    public JsonNode generateFor(UUID user,String code) {
        boolean permitted=db.sql("""
            SELECT EXISTS(SELECT 1 FROM user_account a WHERE a.user_id=:u AND a.status='ACTIVE'
            AND (a.role='SYSTEM_ADMIN' OR (SELECT count(DISTINCT rp.permission_code) FROM role_permission rp
              WHERE rp.role_code=a.role AND rp.permission_code IN ('PORTFOLIO_SELF_READ','SIM_PORTFOLIO_SELF_WRITE'))=2))
            """).param("u",user).query(Boolean.class).single();
        if(!permitted) throw new com.fundradar.core.auth.service.AccessDeniedException();
        legacy.owns(user,code);
        var before=personal(user,code);
        if(!held(before)) throw new IllegalArgumentException("当前已无正份额持仓，请查看历史记录。");
        Instant at=clock.instant();
        JsonNode rating=json.nullNode(),review=json.nullNode(),diagnosis=json.nullNode();
        var errors=new ArrayList<String>();
        try {rating=client.rating(code);}catch(MultiPredictionClient.PredictionServiceFailure e){errors.add("评分暂时无法读取。");}
        try {review=client.get("/funds/"+code+"/review-v2");}catch(MultiPredictionClient.PredictionServiceFailure e){errors.add("四周期分析暂时无法读取。");}
        try {
            var facts=diagnoses.read(code,at.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate());
            diagnosis=json.valueToTree(facts);
            for(int i=0;i<facts.items().size();i++) if(facts.items().get(i).dataAsOfDate()!=null)
                ((ObjectNode)diagnosis.path("items").get(i)).put("dataAsOfDate",facts.items().get(i).dataAsOfDate().toString());
        }
        catch(RuntimeException e){errors.add("补充诊断暂时无法读取，未参与本次判断。");}
        var report=policy.decide(code,before,rating,review,diagnosis,clock.instant());
        for(String error:errors) report.withArray("missingOptionalFactors").add(error);
        if(!errors.isEmpty() && report.hasNonNull("decision")) report.put("adviceState","QUALIFIED");
        if(!errors.isEmpty() && rating.isNull() && review.isNull() && !report.hasNonNull("decision")) {
            report.put("generationStatus","FAILED");report.put("adviceState","FAILED");
            report.putNull("decision");report.put("summary","本次资料读取失败，原建议仍保留，可稍后重试。");
        }
        report.set("personalSnapshot",before);report.set("ratingSnapshot",rating);
        report.set("predictionSnapshot",review);report.set("diagnosisSnapshot",diagnosis);
        report.set("positionSnapshot",before.path("position"));
        // 内容身份包括业务日，不靠可变生成时刻制造不同版本；同日同输入复用原报告。
        report.put("assessmentDate",clock.instant().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().toString());
        String inputHash=DecisionServiceV2.stableHash(report);
        report.put("reportId",UUID.randomUUID().toString());report.put("fundCode",code);
        report.put("generatedAt",clock.instant().toString());report.put("knowledgeCutoff",clock.instant().toString());
        String contentHash=DecisionServiceV2.stableHash(report);
        return tx.execute(status -> {
            // 订单、定投、估值共用账号锁；个人规则共用既有规则锁。没有插入账号或改账。
            db.sql("SELECT user_id FROM sim_account WHERE user_id=:u FOR UPDATE").param("u",user).query(UUID.class).single();
            db.sql("SELECT pg_advisory_xact_lock(721109,hashtext(:key))").param("key",user+":"+code).query((r,n)->0).list();
            db.sql("SELECT pg_advisory_xact_lock(721131,hashtext(:user))").param("user",user.toString()).query((r,n)->0).list();
            var current=personal(user,code);
            if(!before.equals(current) || !held(current)) throw new IllegalArgumentException("持仓、投资偏好或资金安排已变化，请重新生成建议。");
            db.sql("""
                INSERT INTO portfolio_decision_report(report_id,user_id,fund_code,generated_at,strategy_version,
                  generation_status,decision,input_hash,content_hash,payload)
                VALUES(:id,:u,:c,:at,:v,:status,:action,:input,:hash,CAST(:payload AS jsonb))
                ON CONFLICT(user_id,fund_code,input_hash) DO NOTHING
                """).param("id",UUID.fromString(report.path("reportId").asText())).param("u",user).param("c",code)
                .param("at",Timestamp.from(Instant.parse(report.path("generatedAt").asText()))).param("v",HoldingAdvicePolicy.VERSION)
                .param("status",report.path("generationStatus").asText()).param("action",report.path("decision").isNull()?null:report.path("decision").asText(),java.sql.Types.VARCHAR)
                .param("input",inputHash).param("hash",contentHash).param("payload",report.toString()).update();
            return decode(db.sql("SELECT payload::text FROM portfolio_decision_report WHERE user_id=:u AND fund_code=:c AND input_hash=:hash")
                .param("u",user).param("c",code).param("hash",inputHash).query(String.class).single());
        });
    }
    public JsonNode latest(String code) {
        UUID user=owner(false);legacy.owns(user,code);
        var saved=latestFor(user,code);
        if(saved==null) return legacyView(legacy.latest(code));
        return currentInputApplicability(saved,user,code);
    }
    /** 内部只读回查：仍核本人持仓/偏好、有效期和当前公共输入，不生成新报告。 */
    public JsonNode currentFor(UUID user,String code) {
        legacy.owns(user,code);
        var saved=latestFor(user,code);
        return saved==null?null:currentInputApplicability(saved,user,code);
    }
    private JsonNode currentInputApplicability(JsonNode saved,UUID user,String code) {
        ObjectNode view=(ObjectNode)applicability(saved,user,code);
        if("CURRENT".equals(view.path("applicability").asText())) {
            try {
                if(!saved.path("ratingSnapshot").equals(client.rating(code))
                    || !saved.path("predictionSnapshot").equals(client.get("/funds/"+code+"/review-v2"))) {
                    view.put("applicability","CHANGED");view.put("applicabilityMessage","基金评分或分析已有变化，请重新评估。");
                }
            } catch(MultiPredictionClient.PredictionServiceFailure e) {
                view.put("applicability","UNVERIFIED");view.put("applicabilityMessage","暂时无法核对最新资料，下方保留已保存原文。");
            }
        }
        return view;
    }
    public List<JsonNode> latestMine() {
        UUID user=owner(false);
        var result=new TreeMap<String,JsonNode>();
        for(var old:legacy.latestMine()) result.put(old.path("fundCode").asText(),legacyView(old));
        for(var row:db.sql("SELECT DISTINCT ON(fund_code) payload::text FROM portfolio_decision_report WHERE user_id=:u AND strategy_version IN (:v,:previous) ORDER BY fund_code,generated_at DESC,report_id DESC")
                .param("u",user).param("v",HoldingAdvicePolicy.VERSION).param("previous",HoldingAdvicePolicy.PREVIOUS_VERSION).query(String.class).list()) {
            var report=decode(row);String code=report.path("fundCode").asText();result.put(code,currentInputApplicability(report,user,code));
        }
        return List.copyOf(result.values());
    }
    public List<JsonNode> history(String code,int page) { return history(code,page,HoldingAdvicePolicy.VERSION); }
    public List<JsonNode> history(String code,int page,String version) {
        UUID user=owner(false);legacy.owns(user,code);
        if(page<1 || page>10000 || !Set.of(HoldingAdvicePolicy.VERSION,HoldingAdvicePolicy.PREVIOUS_VERSION).contains(version)) throw new IllegalArgumentException("页码不正确");
        return db.sql("SELECT payload::text FROM portfolio_decision_report WHERE user_id=:u AND fund_code=:c AND strategy_version=:v ORDER BY generated_at DESC,report_id DESC LIMIT 20 OFFSET :offset")
            .param("u",user).param("c",code).param("v",version).param("offset",(page-1)*20)
            .query(String.class).list().stream().<JsonNode>map(this::decode).toList();
    }
    private JsonNode latestFor(UUID user,String code) {
        return db.sql("SELECT payload::text FROM portfolio_decision_report WHERE user_id=:u AND fund_code=:c AND strategy_version IN (:v,:previous) ORDER BY generated_at DESC,report_id DESC LIMIT 1")
            .param("u",user).param("c",code).param("v",HoldingAdvicePolicy.VERSION).param("previous",HoldingAdvicePolicy.PREVIOUS_VERSION).query(String.class).optional().map(this::decode).orElse(null);
    }
    private JsonNode legacyView(JsonNode original) {
        if(original==null) return null;
        ObjectNode result=original.deepCopy();result.put("applicability","LEGACY");result.put("applicabilityMessage","这是旧规则保存的建议，请重新生成后查看当前判断。");return result;
    }
    private JsonNode applicability(JsonNode original,UUID user,String code) {
        if(!HoldingAdvicePolicy.VERSION.equals(original.path("strategyVersion").asText())) return legacyView(original);
        ObjectNode view=original.deepCopy();String state="CURRENT",message="";
        if(!original.path("personalSnapshot").equals(personal(user,code))) {state="CHANGED";message="持仓、投资偏好或资金安排已变化，请重新评估。";}
        else if(!Instant.parse(original.path("validUntil").asText()).isAfter(clock.instant())) {state="EXPIRED";message="这份建议的适用时间已过，请重新评估。";}
        view.put("applicability",state);view.put("applicabilityMessage",message);return view;
    }
    private boolean held(JsonNode personal) {
        try{return new BigDecimal(personal.path("position").path("shares").asText("0")).signum()>0;}catch(NumberFormatException e){return false;}
    }
    /** 单条SQL固定所有个人输入，避免多次读取碰上结算而拼成不存在的状态。 */
    JsonNode personal(UUID user,String code) {
        String value=db.sql("""
            SELECT jsonb_build_object(
              'preference',(SELECT preference FROM user_strategy_preference WHERE user_id=:u),
              'funding',(SELECT jsonb_build_object('preferenceId',f.preference_id,'status',f.status,'input',f.payload,'confirmedAt',f.confirmed_at)
                FROM account_funding_preference f WHERE f.user_id=:u ORDER BY confirmed_at DESC,revision DESC,scope DESC,preference_id DESC LIMIT 1),
              'position',(SELECT p.snapshot || jsonb_build_object('issue',p.issue,
                'frozenShares',COALESCE((SELECT sum(o.shares) FROM sim_order o WHERE o.user_id=:u AND o.fund_code=:c AND o.status='PENDING' AND o.side='SELL'),0)::text,
                'availableShares',GREATEST(0,(p.snapshot->>'shares')::numeric-COALESCE((SELECT sum(o.shares) FROM sim_order o WHERE o.user_id=:u AND o.fund_code=:c AND o.status='PENDING' AND o.side='SELL'),0))::text)
                FROM sim_position p WHERE p.user_id=:u AND p.fund_code=:c),
              'orders',COALESCE((SELECT jsonb_agg(jsonb_build_object('orderId',o.order_id,'side',o.side,'amount',o.amount::text,'shares',o.shares::text,'eligibleDate',o.eligible_date) ORDER BY o.order_id)
                FROM sim_order o WHERE o.user_id=:u AND o.fund_code=:c AND o.status='PENDING'),'[]'::jsonb),
              'plans',COALESCE((SELECT jsonb_agg(to_jsonb(p) ORDER BY p.plan_id) FROM sim_plan p WHERE p.user_id=:u AND p.fund_code=:c AND p.status='ACTIVE'),'[]'::jsonb),
              'rule',(SELECT to_jsonb(r) FROM holding_rule_profile r WHERE r.user_id=:u AND r.fund_code=:c AND r.status='ACTIVE'),
              'portfolio',COALESCE((SELECT jsonb_agg(jsonb_build_object('fundCode',p.fund_code,'marketValue',p.snapshot->'marketValue','cost',p.snapshot->'cost','holdingGain',p.snapshot->'holdingGain','navDate',p.snapshot->'navDate','issue',p.issue) ORDER BY p.fund_code)
                FROM sim_position p WHERE p.user_id=:u AND (p.snapshot->>'shares')::numeric>0),'[]'::jsonb)
            )::text
            """).param("u",user).param("c",code).query(String.class).single();
        ObjectNode result=decode(value);var raw=result.path("rule");
        if(!raw.isNull()) {
            var rule=json.createObjectNode();rule.set("ruleId",raw.path("rule_id"));rule.set("takeProfitPct",raw.path("take_profit_pct"));
            rule.set("reduceDrawdownPct",raw.path("reduce_drawdown_pct"));
            rule.put("downsideBasis",raw.path("rule_params").path("downsideBasis").asText("DRAWDOWN"));
            rule.set("source",raw);result.set("rule",rule);
        }
        var position=result.path("position");
        // 旧持仓序列化的LocalDate为数组，统一为业务日期字符串再参与判断。
        if(position.isObject() && position.path("navDate").isArray() && position.path("navDate").size()==3) {
            var d=position.path("navDate");((ObjectNode)position).put("navDate",LocalDate.of(d.get(0).asInt(),d.get(1).asInt(),d.get(2).asInt()).toString());
        }
        for(var item:result.path("portfolio")) {
            var d=item.path("navDate");
            if(d.isArray() && d.size()==3) ((ObjectNode)item).put("navDate",LocalDate.of(d.get(0).asInt(),d.get(1).asInt(),d.get(2).asInt()).toString());
        }
        BigDecimal total=BigDecimal.ZERO;boolean complete=true;
        for(var item:result.path("portfolio")) {
            if(!item.hasNonNull("marketValue") || !item.path("issue").asText("").isBlank()) complete=false;
            else total=total.add(new BigDecimal(item.path("marketValue").asText()));
        }
        result.put("portfolioComplete",complete);
        if(complete) result.put("portfolioMarketValue",total.toPlainString());
        if(complete && total.signum()>0 && position.hasNonNull("marketValue")) result.put("knownWeightPct",new BigDecimal(position.path("marketValue").asText()).multiply(BigDecimal.valueOf(100)).divide(total,2,RoundingMode.HALF_UP).toPlainString());
        return result;
    }
    private ObjectNode decode(String value) {
        try{return (ObjectNode)json.readTree(value);}catch(Exception e){throw new IllegalStateException("建议资料无法读取",e);}
    }
}
