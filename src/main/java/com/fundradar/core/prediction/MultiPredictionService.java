package com.fundradar.core.prediction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fundradar.core.auth.CurrentUserContext;
import com.fundradar.core.auth.PermissionCode;
import com.fundradar.core.direction1d.Direction1dRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import java.util.*;

/** 本人关注授权、公共原文引用、任务归属；没有持仓或个人实验订阅条件。 */
@Service
public class MultiPredictionService {
    private final MultiPredictionClient client; private final Direction1dRepository scope;
    private final JdbcClient db; private final ObjectMapper json;
    public MultiPredictionService(MultiPredictionClient client,Direction1dRepository scope,JdbcClient db,ObjectMapper json) {
        this.client=client; this.scope=scope; this.db=db; this.json=json;
    }
    public UUID user(boolean write) {
        CurrentUserContext.requirePermission(PermissionCode.FUND_READ);
        var user=CurrentUserContext.requirePermission(PermissionCode.WATCHLIST_SELF_READ).userId();
        if(write) CurrentUserContext.requirePermission(PermissionCode.WATCHLIST_SELF_WRITE);
        return user;
    }
    public void owns(UUID user,String code) {
        if(code==null || !code.matches("[0-9]{6}")) throw new IllegalArgumentException("基金代码格式不正确");
        if(!scope.follows(user,code)) throw new NoSuchElementException("本人未关注该基金");
    }
    public JsonNode current(String code) { UUID user=user(false); owns(user,code); return currentFor(user,code); }

    /** 每日任务只归档指定的本期预测；模拟持仓在保存前后重新核实，不依赖关注列表。 */
    @org.springframework.transaction.annotation.Transactional
    public Map<String,Object> archiveHeld(UUID owner,String code,UUID id,java.time.LocalDate target) {
        if(!scope.predictionEligible(owner,code) || !scope.holds(owner,code))
            throw new NoSuchElementException("本人当前没有该基金持仓");
        JsonNode selected=null;
        for(var prediction:client.get("/funds/"+code).path("predictions")) {
            if(!id.toString().equals(prediction.path("predictionId").asText())) continue;
            if(selected!=null) throw new IllegalStateException("DUPLICATE_PUBLIC_RESULT");
            selected=prediction;
        }
        if(selected==null || !code.equals(selected.path("fundCode").asText())
                || !target.toString().equals(selected.path("startDate").asText())
                || !"COMPREHENSIVE".equals(selected.path("generationMethod").asText()))
            throw new IllegalArgumentException("CURRENT_PREDICTION_IDENTITY_OR_DATE_MISMATCH");
        link(owner,code,selected);
        String saved=db.sql("SELECT payload::text FROM prediction_user_link WHERE user_id=:u AND fund_code=:c AND prediction_id=:id")
                .param("u",owner).param("c",code).param("id",id).query(String.class).single();
        try {
            if(!json.readTree(saved).equals(selected)) throw new IllegalStateException("OWNER_PAYLOAD_MISMATCH");
        } catch(java.io.IOException error) { throw new IllegalStateException("OWNER_PAYLOAD_INVALID"); }
        if(!scope.holds(owner,code)) throw new IllegalStateException("OWNER_REMOVED_DURING_ARCHIVE");
        return Map.of("owner",owner.toString(),"code",code,"predictionId",id.toString(),"targetDate",target.toString(),"verified",true);
    }
    public JsonNode currentFor(UUID user,String code) {
        owns(user,code);
        var value=client.get("/funds/"+code);
        // 即使Python进程尚未更新，Java也不能把旧净值结果继续作为当前综合判断返回。
        var result=(com.fasterxml.jackson.databind.node.ObjectNode)value.deepCopy();
        var accepted=json.createArrayNode();
        for(var prediction:value.path("predictions")) {
            if(!"COMPREHENSIVE".equals(prediction.path("generationMethod").asText())) continue;
            link(user,code,prediction);accepted.add(prediction);
        }
        result.set("predictions",accepted);
        return result;
    }
    /** 正式采用后的四周期分析独立读取，不把复盘、条件意见写成事前预测引用。 */
    public JsonNode reviewV2(String code) {
        owns(user(false),code);
        var value=client.get("/funds/"+code+"/review-v2");
        if(value==null || value.isNull()) return null;
        if(!code.equals(value.path("fundCode").asText()) || !value.path("periods").isArray()
                || value.path("periods").size()!=4) throw new IllegalStateException("第二版分析与当前基金不一致");
        var expected=Set.of("T1","T5_V1","T20_V1","M6_V1");
        var seen=new HashSet<String>();
        for(var period:value.path("periods")) {
            String horizon=period.path("horizonId").asText();
            String kind=period.path("kind").asText();
            if(!expected.contains(horizon) || !seen.add(horizon)
                    || !Set.of("UP","DOWN","ABSTAIN").contains(period.path("decision").asText())
                    || !Set.of("DATED_ANALYSIS","RETROSPECTIVE","CONDITIONAL_OUTLOOK").contains(kind)
                    || !"LOW".equals(period.path("confidence").asText())
                    || ("RETROSPECTIVE".equals(kind) || "CONDITIONAL_OUTLOOK".equals(kind))
                       && !period.path("excludedFromAccuracy").asBoolean())
                throw new IllegalStateException("第二版分析的周期或结果状态不一致");
        }
        return value;
    }
    /** 更新只登记新窗口的审阅请求，不用旧方向冒充本次已完成。 */
    public JsonNode refreshReviewV2(String code) {
        owns(user(true),code);
        var value=client.post("/funds/"+code+"/review-v2/generate",Map.of());
        if(value==null || !Set.of("PENDING_ANALYSIS","WAITING_FOR_NAV","AVAILABLE").contains(value.path("status").asText())
                || value.path("summary").asText().isBlank()) throw new IllegalStateException("分析更新状态不完整");
        return value;
    }
    private void link(UUID user,String code,JsonNode prediction) {
        if(!code.equals(prediction.path("fundCode").asText())) throw new IllegalStateException("公共预测基金不匹配");
        db.sql("""
          INSERT INTO prediction_user_link(user_id,fund_code,prediction_id,model_id,model_hash,activation_revision,payload)
          VALUES(:u,:c,:id,:model,:hash,:revision,CAST(:payload AS jsonb)) ON CONFLICT DO NOTHING
          """).param("u",user).param("c",code).param("id",UUID.fromString(prediction.path("predictionId").asText()))
                .param("model",prediction.path("modelId").asText()).param("hash",prediction.path("modelHash").asText())
                .param("revision",prediction.path("activationRevision").asLong()).param("payload",prediction.toString()).update();
    }
    public JsonNode history(String code,String before,UUID beforeId) {
        UUID user=user(false); owns(user,code);
        if(before!=null) java.time.Instant.parse(before);
        return client.get("/funds/"+code+"/history?limit=30"+(before==null?"":"&before="+java.net.URLEncoder.encode(before,java.nio.charset.StandardCharsets.UTF_8))
                +(beforeId==null?"":"&beforeId="+beforeId));
    }
    public JsonNode historyDays(String code,List<String> dates) {
        owns(user(false),code);
        if(dates.isEmpty()||dates.size()>50||new HashSet<>(dates).size()!=dates.size())
            throw new IllegalArgumentException("INVALID_HISTORY_DATES");
        for(var day:dates) if(!java.time.LocalDate.parse(day).toString().equals(day))
            throw new IllegalArgumentException("INVALID_HISTORY_DATES");
        return client.get("/funds/"+code+"/history-days?dates="+String.join(",",dates));
    }
    /** 回看只作为历史资料返回；不得写公共预测引用或提交到期对错核验。 */
    public JsonNode analysisReviews(String code,String before,UUID beforeId) {
        owns(user(false),code);
        if((before==null)!=(beforeId==null)) throw new IllegalArgumentException("回看分页参数不完整");
        if(before!=null) java.time.Instant.parse(before);
        var result=client.get("/funds/"+code+"/analysis-reviews"+(before==null?"":"?before="
                +java.net.URLEncoder.encode(before,java.nio.charset.StandardCharsets.UTF_8)+"&beforeId="+beforeId));
        for(var item:result.path("items")) {
            if(!code.equals(item.path("fundCode").asText())
                    || !"COMPREHENSIVE_ASOF_REVIEW".equals(item.path("recordType").asText())
                    || !item.path("excludedFromAccuracy").asBoolean())
                throw new IllegalStateException("回看资料与当前基金不一致");
        }
        return result;
    }
    /** 只解释本人当前关注基金的公共原预测；Python 按编号、基金及原文摘要再次核对。 */
    public JsonNode narrative(String code,UUID predictionId) {
        owns(user(false),code);
        var result=client.post("/funds/"+code+"/predictions/"+predictionId+"/narrative",Map.of());
        if(!code.equals(result.path("fundCode").asText())
                || !predictionId.toString().equals(result.path("sourceId").asText()))
            throw new IllegalStateException("解释与原预测不一致");
        var view=(com.fasterxml.jackson.databind.node.ObjectNode)result.deepCopy();
        view.remove(List.of("sourceId","contentHash"));
        view.put("recordId",predictionId.toString()); view.put("kind","multi");
        return view;
    }
    public JsonNode generate(String code) { UUID user=user(true); owns(user,code); return start(List.of(code),user,"SELF"); }
    public JsonNode generateMine() {
        UUID user=user(true); var codes=new ArrayList<String>(); String after="";
        while(true) {
            var page=scope.watchPage(user,after,50); if(page.isEmpty()) break;
            for(var row:page) codes.add(row.get("fund_code").toString());
            after=codes.get(codes.size()-1);
            if(codes.size()>500) throw new IllegalArgumentException("关注超过500只，请分批生成");
        }
        return start(codes,user,"SELF");
    }
    public JsonNode start(List<String> codes,UUID owner,String kind) {
        var result=client.post("/batches",Map.of("fundCodes",codes,"requestKey",UUID.randomUUID()));
        db.sql("INSERT INTO prediction_task_owner(task_id,user_id,scope) VALUES(:id,:u,:scope) ON CONFLICT DO NOTHING")
                .param("id",UUID.fromString(result.path("taskId").asText())).param("u",owner).param("scope",kind).update();
        return result;
    }
    public JsonNode task(UUID id,boolean retry) {
        UUID user=user(retry);
        boolean owned=db.sql("SELECT EXISTS(SELECT 1 FROM prediction_task_owner WHERE task_id=:id AND user_id=:u)")
                .param("id",id).param("u",user).query(Boolean.class).single();
        if(!owned) throw new NoSuchElementException("本人没有该任务");
        var original=client.get("/batches/"+id);
        // 移除关注后不能通过旧任务重试；也不向其他用户暴露这批基金。
        for(var item:original.path("items")) owns(user,item.path("fundCode").asText());
        if(!retry) return original;
        var result=client.post("/batches/"+id+"/retry",Map.of());
        db.sql("INSERT INTO prediction_task_owner(task_id,user_id,scope) VALUES(:id,:u,'SELF') ON CONFLICT DO NOTHING")
                .param("id",UUID.fromString(result.path("taskId").asText())).param("u",user).update();
        return result;
    }
    public JsonNode latestTask() {
        UUID owner=user(false);
        var id=db.sql("SELECT task_id FROM prediction_task_owner WHERE user_id=:u ORDER BY created_at DESC LIMIT 1")
                .param("u",owner).query(UUID.class).optional();
        if(id.isEmpty()) return null;
        try { return task(id.get(),false); } catch(NoSuchElementException removedScope) { return null; }
    }
    public JsonNode models() { CurrentUserContext.requirePermission(PermissionCode.MODEL_EXPERIMENT_ADMIN); return client.get("/models"); }
}
