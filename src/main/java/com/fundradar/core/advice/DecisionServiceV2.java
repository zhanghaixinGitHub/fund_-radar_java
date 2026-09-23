package com.fundradar.core.advice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fundradar.core.auth.CurrentUserContext;
import com.fundradar.core.auth.PermissionCode;
import com.fundradar.core.direction1d.Direction1dPolicy;
import com.fundradar.core.prediction.MultiPredictionClient;
import com.fundradar.core.simulation.SimulationRepository;
import com.fundradar.core.simulation.SimulationTypes;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import static com.fundradar.core.advice.DecisionPolicyV2.*;

/** 综合建议快照：公共预测、诊断、本人规则/持仓同时固定；失败仍留档，绝不提交模拟交易。 */
@Service
public class DecisionServiceV2 {
    private final JdbcClient db; private final ObjectMapper json; private final MultiPredictionClient predictions;
    private final SimulationRepository positions; private final DecisionPolicyV2 policy;
    private final DiagnosisClient diagnoses; private final RuleRepository rules; private final Clock clock;
    public DecisionServiceV2(JdbcClient db,ObjectMapper json,MultiPredictionClient predictions,SimulationRepository positions,
                             DecisionPolicyV2 policy,DiagnosisClient diagnoses,RuleRepository rules,Clock clock) {
        this.db=db;this.json=json;this.predictions=predictions;this.positions=positions;this.policy=policy;
        this.diagnoses=diagnoses;this.rules=rules;this.clock=clock;
    }
    private UUID user(boolean write) {
        var user=CurrentUserContext.requirePermission(PermissionCode.PORTFOLIO_SELF_READ).userId();
        if(write) CurrentUserContext.requirePermission(PermissionCode.SIM_PORTFOLIO_SELF_WRITE);
        return user;
    }
    public void owns(UUID user,String code) {
        if(code==null||!code.matches("[0-9]{6}")) throw new IllegalArgumentException("基金代码不正确");
        boolean found=db.sql("""
          SELECT EXISTS(SELECT 1 FROM sim_position WHERE user_id=:u AND fund_code=:c)
          OR EXISTS(SELECT 1 FROM watchlist_item WHERE user_id=:u AND fund_code=:c)
          """).param("u",user).param("c",code).query(Boolean.class).single();
        if(!found) throw new NoSuchElementException("本人没有该持仓或关注关系");
    }
    public JsonNode generate(String code) { UUID user=user(true); owns(user,code); return generateFor(user,code,true); }
    public JsonNode latest(String code) {
        UUID user=user(false); owns(user,code);
        return db.sql("SELECT payload::text FROM portfolio_decision_report WHERE user_id=:u AND fund_code=:c ORDER BY generated_at DESC LIMIT 1")
                .param("u",user).param("c",code).query(String.class).optional().map(this::decode).orElse(null);
    }
    /** 持仓总览一次读取本人各基金最新建议，避免每张卡片分别访问数据库和上游。 */
    public List<JsonNode> latestMine() {
        UUID owner=user(false);
        return db.sql("SELECT DISTINCT ON(fund_code) payload::text FROM portfolio_decision_report WHERE user_id=:u ORDER BY fund_code,generated_at DESC LIMIT 500")
                .param("u",owner).query(String.class).list().stream().map(this::decode).toList();
    }
    public List<JsonNode> history(String code,int page,String version) {
        UUID user=user(false); owns(user,code);
        if(page<1||page>10000||!VERSION.equals(version)) throw new IllegalArgumentException("版本或页码不正确");
        return db.sql("""
          SELECT payload::text FROM portfolio_decision_report WHERE user_id=:u AND fund_code=:c AND strategy_version=:v
          ORDER BY generated_at DESC,report_id LIMIT 20 OFFSET :offset
          """).param("u",user).param("c",code).param("v",version).param("offset",(page-1)*20)
                .query(String.class).list().stream().map(this::decode).toList();
    }
    public JsonNode report(String code,UUID id) {
        UUID user=user(false); owns(user,code);
        var row=db.sql("SELECT payload::text,content_hash FROM portfolio_decision_report WHERE user_id=:u AND fund_code=:c AND report_id=:id")
                .param("u",user).param("c",code).param("id",id).query().listOfRows().stream().findFirst().orElseThrow(NoSuchElementException::new);
        var payload=decode((String)row.get("payload"));
        if(!Direction1dPolicy.hash(payload.toString()).equals(row.get("content_hash"))) {
            // JSONB会重排键；校验保存的规范字段而不是数据库文本顺序。
            if(!stableHash(payload).equals(row.get("content_hash"))) throw new IllegalStateException("建议档案校验失败");
        }
        return payload;
    }
    /** 核验结果附加读取，不回写报告原文；批量调用避免每份报告每个模型一次HTTP。 */
    public JsonNode outcomes(String code,int page) {
        var records=history(code,page,VERSION);var ids=new LinkedHashSet<String>();
        for(var report:records) for(var reference:report.path("modelRefs")) ids.add(reference.path("predictionId").asText());
        if(ids.isEmpty()) return json.createObjectNode();
        return predictions.post("/funds/"+code+"/outcomes",Map.of("predictionIds",ids));
    }
    public Map<String,Object> preference() { return preference(user(false)); }
    private Map<String,Object> preference(UUID user) {
        String value=db.sql("SELECT preference FROM user_strategy_preference WHERE user_id=:u").param("u",user)
                .query(String.class).optional().orElse(null);
        return Map.of("preference",value==null?"BALANCED":value,"defaultPreference",value==null,"strategyVersion",VERSION);
    }
    public Map<String,Object> savePreference(String preference) {
        UUID user=user(true);
        if(!Set.of("SHORT","BALANCED","LONG").contains(preference)) throw new IllegalArgumentException("策略偏好不正确");
        db.sql("""
          INSERT INTO user_strategy_preference(user_id,preference) VALUES(:u,:p)
          ON CONFLICT(user_id) DO UPDATE SET preference=excluded.preference,updated_at=clock_timestamp()
          """).param("u",user).param("p",preference).update(); return preference(user);
    }
    /** 调度与手动均走此方法；清仓仍给BUY/AVOID，不读取或写入真实资金。 */
    public JsonNode generateFor(UUID user,String code,boolean refresh) {
        owns(user,code); Instant now=clock.instant();
        var portfolio=positions.positions(user);
        SimulationTypes.Position position=portfolio.stream().filter(p->p.fundCode().equals(code)).findFirst().orElse(null);
        var preference=preference(user); var signals=new ArrayList<Signal>(); var facts=new ArrayList<Fact>();
        var missing=new ArrayList<String>(); var constraints=new ArrayList<String>();
        JsonNode current=null,diagnosisSnapshot=null,ruleSnapshot=null;
        Double trend=null,drawdown=null; Decision decision; ObjectNode publicError=null;
        try {
            if(refresh) {
                var task=predictions.post("/batches",Map.of("fundCodes",List.of(code),"requestKey",UUID.randomUUID()));
                String taskId=task.path("taskId").asText();
                for(int attempt=0;attempt<40 && Set.of("QUEUED","RUNNING","INTERRUPTED").contains(task.path("status").asText());attempt++) {
                    try { Thread.sleep(150); } catch(InterruptedException e) { Thread.currentThread().interrupt(); break; }
                    task=predictions.get("/batches/"+taskId);
                }
            }
            current=predictions.get("/funds/"+code);
            for(var item:current.path("predictions")) {
                // 过期预测不能充当今日输入；长周期未到期可继续用，始终保存原生成日与身份。
                String end=item.path("endDate").asText("");
                String resolved=current.path("targetResolutions").path(item.path("predictionId").asText()).path("endDate").asText("");
                if(!resolved.isBlank()) end=resolved;
                if(end.isBlank()) end=item.path("nominalEndDate").asText("");
                if(!end.isBlank() && LocalDate.parse(end).isBefore(now.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate())) continue;
                signals.add(new Signal(item.path("predictionId").asText(),item.path("horizonId").asText(),item.path("direction").asText(),
                        item.path("modelId").asText(),item.path("modelHash").asText(),item.path("activationRevision").asLong(),item.path("dataAsOf").asText()));
                var feature=item.path("featureSnapshot");
                if(trend==null && feature.path("features").has("trendRiskFactor")) {
                    trend=feature.path("features").path("trendRiskFactor").asDouble();
                    drawdown=feature.path("features").path("currentDrawdown").asDouble();
                    for(var fact:feature.path("facts")) facts.add(new Fact(fact.path("title").asText(),
                            fact.path("value").asText()+"；"+fact.path("explanation").asText(),fact.path("source").asText(),fact.path("factor").asDouble()));
                    for(var factor:feature.path("missingOptionalFactors")) missing.add(factor.asText());
                }
            }
            for(var attempt:current.path("latestAttempts")) if(attempt.path("payload").path("generationStatus").asText().equals("FAILED")) {
                missing.add(attempt.path("horizon_id").asText()+"本次失败："+attempt.path("payload").path("error").path("summary").asText());
                publicError=(ObjectNode)attempt.path("payload").path("error").deepCopy();
            }
            try {
                var diagnosis=diagnoses.read(code,now.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate());
                diagnosisSnapshot=json.valueToTree(diagnosis);
                for(var item:diagnosis.items()) {
                    String title=Map.of("MANAGER","经理任职","SCALE","基金规模","SAME_TYPE_RANK","同类表现",
                            "BENCHMARK","基准对照","DRAWDOWN","回撤","FEE","费用","DIVIDEND","分红").get(item.item());
                    if("INSUFFICIENT".equals(item.verdict())) missing.add(title+"："+item.evidence());
                    else facts.add(new Fact(title,diagnosisEvidence(item)+"（资料截至"+item.dataAsOfDate()+"）",item.source(),0));
                }
            } catch(RuntimeException error) { missing.add("公共诊断接口未取得：经理/规模/同类/基准的补充诊断未参与"); }
            var personal=rules.activeRule(user,code);
            ruleSnapshot=json.valueToTree(personal);
            PersonalRule rule=personal==null?null:new PersonalRule(personal.ruleId().toString(),
                    personal.takeProfitPct()==null?null:personal.takeProfitPct().doubleValue(),
                    personal.reduceDrawdownPct()==null?null:personal.reduceDrawdownPct().doubleValue());
            if(position!=null && position.frozenShares().signum()>0) constraints.add("部分份额尚冻结，动作不等于立即全部可执行");
            var knownTotal=portfolio.stream().map(SimulationTypes.Position::marketValue).filter(Objects::nonNull)
                    .reduce(java.math.BigDecimal.ZERO,java.math.BigDecimal::add);
            if(position!=null && position.marketValue()!=null && knownTotal.signum()>0)
                constraints.add("本基金占已知模拟持仓市值"+position.marketValue().multiply(java.math.BigDecimal.valueOf(100))
                        .divide(knownTotal,2,java.math.RoundingMode.HALF_UP)+"%；缺少底层行业暴露及独立现金账，未据此推断完整组合风险");
            constraints.add("只形成实验判断，不自动下单；实际申赎开放、费用与到账以交易渠道为准");
            decision=policy.decide(new Input(signals,trend,facts,missing,position!=null&&position.shares().signum()>0,
                    preference.get("preference").toString(),(boolean)preference.get("defaultPreference"),
                    position==null||position.holdingGainRate()==null?null:position.holdingGainRate().doubleValue(),
                    drawdown,rule,constraints));
        } catch(MultiPredictionClient.PredictionServiceFailure error) {
            decision=policy.failed(error.detail().code(),error.detail().summary(),missing);
        }
        // 输入收齐后确定本次知识截止；不让报告时间早于本轮实际采用的预测和事实。
        now=clock.instant();
        ObjectNode payload=json.valueToTree(decision);
        payload.put("reportId",UUID.randomUUID().toString()); payload.put("fundCode",code);
        payload.put("generatedAt",now.toString()); payload.put("validUntil",now.plus(Duration.ofDays(1)).toString());
        payload.put("knowledgeCutoff",now.toString());
        payload.set("positionSnapshot",json.valueToTree(position));
        payload.put("positionSnapshotId",stableHash(json.valueToTree(position)));
        payload.set("predictionSnapshot",current==null?json.nullNode():current);
        // 发布身份来自本次实际引用原文，不能用当前路由替旧预测补填。
        var releases=new TreeSet<String>();
        if(current!=null) for(var item:current.path("predictions")) {
            if(signals.stream().anyMatch(signal->signal.predictionId().equals(item.path("predictionId").asText()))
                    && !item.path("releaseId").isNull() && item.hasNonNull("releaseId")) releases.add(item.path("releaseId").asText());
        }
        payload.set("releaseIds",json.valueToTree(releases));
        payload.set("diagnosisSnapshot",diagnosisSnapshot==null?json.nullNode():diagnosisSnapshot);
        payload.set("personalRuleSnapshot",ruleSnapshot==null?json.nullNode():ruleSnapshot);
        payload.set("portfolioSnapshot",json.valueToTree(portfolio));
        if("FAILED".equals(decision.generationStatus())&&publicError!=null) payload.set("error",publicError);
        ObjectNode identity=payload.deepCopy(); identity.remove(List.of("reportId","generatedAt","validUntil","knowledgeCutoff"));
        String inputHash=stableHash(identity),hash=stableHash(payload);
        db.sql("""
          INSERT INTO portfolio_decision_report(report_id,user_id,fund_code,generated_at,strategy_version,
          generation_status,decision,input_hash,content_hash,payload)
          VALUES(:id,:u,:c,:now,:version,:status,:decision,:input,:hash,CAST(:payload AS jsonb)) ON CONFLICT DO NOTHING
          """).param("id",UUID.fromString(payload.path("reportId").asText())).param("u",user).param("c",code)
                .param("now",Timestamp.from(now)).param("version",VERSION).param("status",decision.generationStatus())
                .param("decision",decision.decision(),java.sql.Types.VARCHAR).param("input",inputHash).param("hash",hash).param("payload",payload.toString()).update();
        return db.sql("SELECT payload::text FROM portfolio_decision_report WHERE user_id=:u AND fund_code=:c AND input_hash=:hash")
                .param("u",user).param("c",code).param("hash",inputHash).query(String.class).single().transform(this::decode);
    }
    private JsonNode decode(String value) { try { return json.readTree(value); } catch(Exception e) { throw new IllegalStateException("建议档案无法读取",e); } }
    /** 新综合建议用业务含义展示；完整原始字段仍在diagnosisSnapshot，旧报告不改写。 */
    private String diagnosisEvidence(DiagnosisTypes.FactItem item) {
        var facts=item.facts();
        if(facts!=null && "FEE".equals(item.item())) return "当前年管理费率"+facts.get("management_fee")+
                "%、年托管费率"+facts.get("custodian_fee")+"%；费用已反映在净值，不重复扣除；历史费率尚不完整";
        if(facts!=null && "SAME_TYPE_RANK".equals(item.item())) {
            var change=new java.math.BigDecimal(facts.get("month_change_rate").toString()).multiply(java.math.BigDecimal.valueOf(100));
            return "当前已接入的"+facts.get("comparable_count")+"只同类样本中排名"+facts.get("rank")+
                    "，近一月涨跌"+change.setScale(2,java.math.RoundingMode.HALF_UP)+"%；仅为已接入样本，不代表全市场排名";
        }
        return item.evidence().replaceAll("[^。]*Java[^。]*。", "")
                .replace("ACCUMULATED 口径", "累计净值口径");
    }
    private String stableHash(JsonNode node) {
        if(node==null||node.isNull()) return Direction1dPolicy.hash("null");
        if(node.isObject()) {
            var sorted=new TreeMap<String,JsonNode>(); node.fields().forEachRemaining(e->sorted.put(e.getKey(),e.getValue()));
            return Direction1dPolicy.hash(sorted.entrySet().stream().map(e->e.getKey()+":"+stableHash(e.getValue())).toList().toString());
        }
        if(node.isArray()) { var parts=new ArrayList<String>(); node.forEach(v->parts.add(stableHash(v))); return Direction1dPolicy.hash(parts.toString()); }
        return Direction1dPolicy.hash(node.toString());
    }
}
