package com.fundradar.core.direction1d;

import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.time.Duration;
import java.util.*;

/** 手动同步的预测留档链路；按基金统计，多个账号关注不重复计算基金只数。 */
@Service
public class Direction1dBatchService {
    private final Direction1dRepository repo;
    private final Direction1dService service;
    private final Direction1dClient client;

    public Direction1dBatchService(Direction1dRepository repo,Direction1dService service,Direction1dClient client) {
        this.repo=repo; this.service=service; this.client=client;
    }

    public List<String> fundCodes(String after) { return repo.predictionFundCodes(after); }

    public List<String> fundCodes(String after,UUID owner) { return repo.predictionFundCodes(after,owner); }

    /** 只为触发账号登记预测；即使同步过程中取消关注或停用，也不能继续关联结果。 */
    public Map<String,Object> generateForUser(String code,LocalDate target,UUID owner) {
        if(code==null || !code.matches("[0-9]{6}") || target==null || owner==null)
            throw new IllegalArgumentException("INVALID_PREDICTION_SCOPE");
        if(!repo.predictionEligible(owner,code)) return Map.of("status","NO_LONGER_FOLLOWED");
        var w=service.checkedStatus().path("window");
        if(!target.toString().equals(w.path("target_nav_date").asText())) return Map.of("status","WINDOW_CHANGED");
        var coverage=client.coverage(List.of(code)).path("items").get(0);
        UUID scope=repo.scope(owner,target,List.of(Map.of("fund_code",code,"coverage",Direction1dPolicy.view(coverage))));
        return service.process(owner,scope,coverage,w,Duration.ofSeconds(30));
    }

    /** 开始批次时冻结目标日；后续执行跨过下一期起点时不得悄悄更换预测日期。 */
    public Map<String,Object> window() {
        var w=service.checkedStatus().path("window");
        return Map.of("targetNavDate",w.path("target_nav_date").asText());
    }

    /** 目标日必须由批次开始时的服务端窗口取得；截止、适用性和完整数据检查继续生效。 */
    public Map<String,Object> generate(String code,LocalDate target) {
        if(code==null || !code.matches("[0-9]{6}") || target==null) throw new IllegalArgumentException("INVALID_PREDICTION_SCOPE");
        var w=service.checkedStatus().path("window");
        if(!target.toString().equals(w.path("target_nav_date").asText())) return Map.of("status","WINDOW_CHANGED");
        var coverage=client.coverage(List.of(code)).path("items").get(0);
        UUID after=null;
        boolean created=false;
        // 同一基金公共计算只等待一次，避免关注账号多时累乘等待而超过内部HTTP超时。
        Duration waitBudget=Duration.ofSeconds(30);
        Map<String,Object> result=Map.of("status","NO_LONGER_FOLLOWED");
        do {
            var users=repo.predictionUsers(code,after);
            if(users.isEmpty()) break;
            for(UUID user:users) {
                UUID scope=repo.scope(user,target,List.of(Map.of("fund_code",code,"coverage",Direction1dPolicy.view(coverage))));
                result=service.process(user,scope,coverage,w,waitBudget);
                waitBudget=Duration.ZERO;
                if("PREDICTED".equals(result.get("status")) && !Boolean.TRUE.equals(result.get("reused"))) created=true;
            }
            after=users.get(users.size()-1);
        } while(true);
        if("PREDICTED".equals(result.get("status"))) return Map.of("status","PREDICTED","reused",!created);
        return result;
    }

    /**
     * 只核对已提交的指定作业；不会创建预测、更新输入或把目标日切到下一期。
     * 成功必须通过原有摘要、截止时间和留档回读校验，并关联原任务内仍有效的关注账号。
     */
    public Map<String,Object> reconcile(String code,LocalDate target,UUID job) {
        return reconcile(code,target,job,null);
    }

    public Map<String,Object> reconcile(String code,LocalDate target,UUID job,UUID owner) {
        if(code==null || !code.matches("[0-9]{6}") || target==null || job==null)
            throw new IllegalArgumentException("INVALID_PREDICTION_SCOPE");
        var scopes=owner==null?repo.syncJobScopes(code,target,job,null):repo.syncJobScopes(code,target,job,null,owner);
        if(scopes.isEmpty()) return Map.of("status","FAILED","reason","JOB_SCOPE_UNAVAILABLE");
        var result=client.get("/forecast-jobs/"+job);
        var input=result.path("payload");
        if(!"FORECAST".equals(result.path("kind").asText()) || !code.equals(input.path("fund_code").asText())
                || !target.toString().equals(input.path("target_nav_date").asText()))
            throw new IllegalArgumentException("JOB_SCOPE_MISMATCH");
        String state=result.path("state").asText();
        if(Set.of("QUEUED","RUNNING").contains(state)) return Map.of("status",state,"jobId",job);
        if(!"SUCCEEDED".equals(state)) {
            String reason=result.path("result").path("reason").asText("PREDICTION_FAILED");
            finish(code,target,job,"FAILED",reason,owner);
            return Map.of("status","FAILED","reason",reason);
        }
        var payload=result.path("result");
        if(!target.toString().equals(repo.decode(payload.path("payload_json").asText()).path("target_nav_date").asText()))
            throw new IllegalArgumentException("JOB_SCOPE_MISMATCH");
        Direction1dRepository.ArchivedForecast saved;
        try {
            saved=repo.archive(job,payload.path("payload_json").asText(),payload.path("content_hash").asText(),code);
        } catch(IllegalArgumentException error) {
            // 到期后才取得的回执不能冒充截止前已保存；其他校验拒绝也保留旧判断。
            String reason="MISSED_DEADLINE".equals(error.getMessage())?"ARCHIVE_DEADLINE_PASSED":"ARCHIVE_REJECTED";
            finish(code,target,job,"FAILED",reason,owner);
            return Map.of("status","FAILED","reason",reason);
        }
        do {
            for(var scope:scopes) repo.link((UUID)scope.get("user_id"),saved.forecastId(),(UUID)scope.get("snapshot_id"));
            UUID after=(UUID)scopes.get(scopes.size()-1).get("snapshot_id");
            scopes=owner==null?repo.syncJobScopes(code,target,job,after):repo.syncJobScopes(code,target,job,after,owner);
        } while(!scopes.isEmpty());
        finish(code,target,job,"PREDICTED",null,owner);
        return Map.of("status","PREDICTED","reused",!saved.created());
    }

    private void finish(String code,LocalDate target,UUID job,String state,String reason,UUID owner) {
        if(owner==null) repo.finishSyncJob(code,target,job,state,reason);
        else repo.finishSyncJob(code,target,job,state,reason,owner);
    }
}
