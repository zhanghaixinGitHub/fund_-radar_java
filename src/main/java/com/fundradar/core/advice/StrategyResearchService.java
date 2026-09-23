package com.fundradar.core.advice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fundradar.core.auth.CurrentUserContext;
import com.fundradar.core.auth.PermissionCode;
import com.fundradar.core.direction1d.Direction1dPolicy;
import com.fundradar.core.prediction.MultiPredictionClient;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** 独立研究账本：一直持有与冻结的多周期模型组合对照，不触碰个人模拟现金或切换模型。 */
@Service
public class StrategyResearchService {
    private final MultiPredictionClient client;private final JdbcClient db;private final ObjectMapper json;
    private final StrategyReplayEngine engine;
    public StrategyResearchService(MultiPredictionClient client,JdbcClient db,ObjectMapper json,StrategyReplayEngine engine) {
        this.client=client;this.db=db;this.json=json;this.engine=engine;
    }
    public record Request(String fundCode,LocalDate startDate,LocalDate endDate) {}
    public JsonNode run(Request request) {
        UUID user=CurrentUserContext.requirePermission(PermissionCode.RESEARCH_RUN_ADMIN).userId();
        validateRequest(request,LocalDate.now(ZoneId.of("Asia/Shanghai")));
        UUID id=UUID.randomUUID();ObjectNode spec=json.valueToTree(request);spec.set("executionPolicy",json.valueToTree(StrategyReplayEngine.defaultConfig()));
        db.sql("INSERT INTO strategy_replay_run(run_id,user_id,status,spec) VALUES(:id,:u,'RUNNING',CAST(:spec AS jsonb))")
                .param("id",id).param("u",user).param("spec",spec.toString()).update();
        ObjectNode result=json.createObjectNode();result.put("runId",id.toString());
        try {
            var inputs=client.get("/funds/"+request.fundCode()+"/replay-inputs?start="+request.startDate()+"&end="+request.endDate());
            result.setAll(compareModels(inputs));
        } catch(Exception error) {
            result.put("status","FAILED");result.put("errorCode",error instanceof MultiPredictionClient.PredictionServiceFailure e?e.detail().code():"STRATEGY_REPLAY_FAILED");
            result.put("errorMessage",error instanceof MultiPredictionClient.PredictionServiceFailure e?e.detail().summary():"回放未完成，请查研究任务日志");
            org.slf4j.LoggerFactory.getLogger(getClass()).error("StrategyResearchService.run   >>> runId={}, failed",id,error);
        }
        result.put("resultHash",Direction1dPolicy.hash(result.toString()));
        db.sql("UPDATE strategy_replay_run SET status=:status,result=CAST(:result AS jsonb),finished_at=clock_timestamp() WHERE run_id=:id AND user_id=:u")
                .param("status",result.path("status").asText()).param("result",result.toString()).param("id",id).param("u",user).update();
        return result;
    }

    /**
     * 模型只改变预测输入，资金、日期、费用和决策规则保持相同。
     * 完整核对每一天实际模型身份，防止候选名称下混入基础回退包，或少算失败日期得到虚高成绩。
     */
    public ObjectNode compareModels(JsonNode inputs) throws com.fasterxml.jackson.core.JsonProcessingException {
        return compareModels(inputs,StrategyReplayEngine.defaultConfig());
    }

    /** 自动周期由冻结协议提供费用，继续复用同一个成交账本，不按候选各自设置资金。 */
    public ObjectNode compareModels(JsonNode inputs,StrategyReplayEngine.Config executionPolicy) throws com.fasterxml.jackson.core.JsonProcessingException {
        if(!"MODEL_BUNDLE_REPLAY_V1".equals(inputs.path("comparisonVersion").asText()))
            throw new IllegalArgumentException("模型比较输入版本未就绪，请更新预测服务");
        var frames=new ArrayList<StrategyReplayEngine.Frame>();
        for(var frame:inputs.path("frames")) frames.add(json.treeToValue(frame,StrategyReplayEngine.Frame.class));
        var bundles=inputs.path("modelComparisons");
        if(!bundles.isArray() || bundles.size()>4) throw new IllegalArgumentException("模型组合数量不正确");
        ObjectNode result=json.createObjectNode();
        var comparisons=result.putObject("comparisons");
        var models=result.putObject("comparisonModels");
        comparisons.set("BUY_HOLD",json.valueToTree(engine.run(frames,executionPolicy,"BUY_HOLD")));
        models.putObject("BUY_HOLD").put("label","买入后一直持有").put("role","BENCHMARK");
        var ids=new HashSet<String>();
        for(var bundle:bundles) {
            String id=bundle.path("id").asText();
            if(!id.matches("MODEL_[a-f0-9]{16}") || !ids.add(id)) throw new IllegalArgumentException("模型组合编号不正确");
            var refs=new HashMap<String,JsonNode>();
            for(var ref:bundle.path("modelRefs")) {
                if(refs.put(ref.path("horizonId").asText(),ref)!=null) throw new IllegalArgumentException("组合周期重复");
            }
            if(!refs.keySet().equals(Set.of("T5_V1","T20_V1","M6_V1"))) throw new IllegalArgumentException("模型组合必须覆盖全部三个周期");
            if(bundle.path("frames").size()!=frames.size()) throw new IllegalArgumentException("模型组合比较日期不一致");
            var modelFrames=new ArrayList<StrategyReplayEngine.Frame>();
            int index=0;
            for(var raw:bundle.path("frames")) {
                var frame=json.treeToValue(raw,StrategyReplayEngine.Frame.class);
                var base=frames.get(index++);
                if(!frame.date().equals(base.date()) || frame.nav().compareTo(base.nav())!=0
                        || !Objects.equals(frame.cashDividend(),base.cashDividend())
                        || frame.redemptionPaused()!=base.redemptionPaused()) throw new IllegalArgumentException("模型组合的行情条件不同");
                // 除 predictions 外，其他决策输入也必须完全一致。
                var actualInput=(ObjectNode)json.valueToTree(frame.input());actualInput.remove("predictions");
                var baseInput=(ObjectNode)json.valueToTree(base.input());baseInput.remove("predictions");
                if(!actualInput.equals(baseInput)) throw new IllegalArgumentException("模型组合的决策条件不同");
                var seen=new HashSet<String>();
                for(var signal:frame.input().predictions()) {
                    var ref=refs.get(signal.horizonId());
                    if(ref==null || !seen.add(signal.horizonId())
                            || !com.fundradar.core.prediction.PredictionDirectionContract.valid(signal.direction(),signal.targetDefinitionId(),signal.directionPolicyHash())
                            || !ref.path("modelId").asText().equals(signal.modelId())
                            || !ref.path("modelHash").asText().equals(signal.modelHash())
                            || ref.path("activationRevision").asLong()!=signal.activationRevision())
                        throw new IllegalArgumentException("实际推理模型与声明不一致");
                }
                if(!seen.equals(refs.keySet())) throw new IllegalArgumentException("该日多周期预测不完整");
                modelFrames.add(frame);
            }
            comparisons.set(id,json.valueToTree(engine.run(modelFrames,executionPolicy,"V2")));
            var metadata=bundle.deepCopy();((ObjectNode)metadata).remove("frames");models.set(id,metadata);
        }
        result.set("inputSnapshot",inputs);result.set("excludedModels",inputs.path("excludedModels"));
        result.put("status","SUCCEEDED");
        result.put("comparisonProtocol","MODEL_BUNDLE_REPLAY_V1：同基金/日期/本金/费用/决策规则，只更换冻结的多周期预测模型；本次结果不自动采用赢家");
        return result;
    }
    /**
     * 在创建研究任务前逐项校验，避免把“选了今天”误报成“超过两年”。
     * today 按北京时间传入；仅使用已经过去的日期，不把尚未结束的当天当作完整历史。
     * 732 天沿用 Python 回放输入的既有上限，不改变本轮实验样本规模。
     */
    static void validateRequest(Request request,LocalDate today) {
        if(request==null||request.fundCode()==null||!request.fundCode().matches("[0-9]{6}"))
            throw new IllegalArgumentException("请输入一只基金的6位代码，例如006730");
        if(request.startDate()==null||request.endDate()==null)
            throw new IllegalArgumentException("请选择开始日期和结束日期");
        if(!request.startDate().isBefore(request.endDate()))
            throw new IllegalArgumentException("开始日期必须早于结束日期");
        if(!request.endDate().isBefore(today))
            throw new IllegalArgumentException("结束日期必须早于今天，请选择"+today.minusDays(1)+"或更早的日期；当天尚未结束，不能作为完整历史回放");
        if(ChronoUnit.DAYS.between(request.startDate(),request.endDate())>732)
            throw new IllegalArgumentException("本次日期跨度超过两年（732天），请缩短开始日期与结束日期之间的范围");
    }
    public JsonNode read(UUID id) {
        UUID user=CurrentUserContext.requirePermission(PermissionCode.RESEARCH_RUN_ADMIN).userId();
        String value=db.sql("SELECT result::text FROM strategy_replay_run WHERE run_id=:id AND user_id=:u").param("id",id).param("u",user)
                .query(String.class).optional().orElseThrow(NoSuchElementException::new);
        try {return json.readTree(value);}catch(Exception error) {throw new IllegalStateException("研究结果无法读取",error);}
    }
}
