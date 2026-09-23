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

/** 受权限和单次规模控制的独立研究账本，保留输入和四组对照，不触碰个人模拟现金。 */
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
            var frames=new ArrayList<StrategyReplayEngine.Frame>();
            for(var frame:inputs.path("frames")) frames.add(json.treeToValue(frame,StrategyReplayEngine.Frame.class));
            var comparisons=result.putObject("comparisons");
            for(String mode:List.of("V2","V1","BUY_HOLD","V2_WITHOUT_EVENTS")) comparisons.set(mode,json.valueToTree(engine.run(frames,StrategyReplayEngine.defaultConfig(),mode)));
            result.set("inputSnapshot",inputs);result.put("status","SUCCEEDED");
            var v2=comparisons.path("V2");var hold=comparisons.path("BUY_HOLD");
            double increment=v2.path("netReturn").asDouble()-hold.path("netReturn").asDouble();
            result.put("excessReturnVsHold",increment);
            result.put("eventIncrement",v2.path("netReturn").asDouble()-comparisons.path("V2_WITHOUT_EVENTS").path("netReturn").asDouble());
            result.put("eventAdoptionDecision","KEEP_NEUTRAL_FACTS：本批公告无方向规则，增量为零，保留事实不提高权重");
            result.put("strategyAdoptionDecision",increment>0?"本开发区间收益高于一直持有；保留V2实验，尚非长期优势证明":"本开发区间未超过一直持有；保留失败结论和V2实验身份，不声称策略有效");
            result.put("comparisonProtocol","STRATEGY_COMPARISON_V1：同基金/日历/初资/费用/到账，净收益主指标；回撤、换手和空仓期同时披露");
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
