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
    public JsonNode currentFor(UUID user,String code) {
        owns(user,code);
        var value=client.get("/funds/"+code);
        for(var prediction:value.path("predictions")) link(user,code,prediction);
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
