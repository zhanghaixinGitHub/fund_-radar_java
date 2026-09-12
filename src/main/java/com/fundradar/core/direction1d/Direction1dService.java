package com.fundradar.core.direction1d;

import com.fasterxml.jackson.databind.JsonNode;
import com.fundradar.core.auth.CurrentUserContext;
import com.fundradar.core.auth.PermissionCode;
import com.fundradar.core.watchlist.service.WatchlistRequiredException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

/** 个人范围由Java认证上下文产生；公共计算按基金去重，打开页面不触发训练或同步。 */
@Service
public class Direction1dService {
    private final Direction1dRepository repo; private final Direction1dClient client;
    private final boolean enabled; private final boolean forecastEnabled;
    public Direction1dService(Direction1dRepository repo,Direction1dClient client,
            @Value("${direction1d.enabled:false}") boolean enabled,@Value("${direction1d.forecast-enabled:true}") boolean forecastEnabled) {
        this.repo=repo; this.client=client; this.enabled=enabled; this.forecastEnabled=forecastEnabled;
    }
    public UUID user(boolean write) {
        CurrentUserContext.requirePermission(PermissionCode.FUND_READ);
        CurrentUserContext.requirePermission(PermissionCode.WATCHLIST_SELF_READ);
        return CurrentUserContext.requirePermission(write?PermissionCode.WATCHLIST_SELF_WRITE:PermissionCode.WATCHLIST_SELF_READ).userId();
    }
    public Map<String,Object> status() {
        UUID user=user(false); Map<String,Object> out=new LinkedHashMap<>();
        out.put("enabled",repo.enabled(user)); out.put("backendEnabled",enabled); out.put("forecastEnabled",forecastEnabled);
        out.put("modelReleased",false); out.put("upProbability",null); out.put("experimentStatus","EXPERIMENTAL");
        out.put("health",repo.health()); out.put("python",Direction1dPolicy.view(client.get("/status"))); return out;
    }
    public Map<String,Object> subscription(boolean value) {
        UUID user=user(true);
        if(value&&!enabled) throw new IllegalArgumentException("BACKEND_DISABLED");
        repo.subscribe(user,value); return status();
    }
    public Map<String,Object> coverage(String keyword,String type,int page,int size) {
        UUID user=user(false); validatePage(page,size);
        List<Object> visible=new ArrayList<>(); Map<String,Integer> counts=new TreeMap<>(); String after="";
        int checked=0,matched=0; long offset=(long)(page-1)*size;
        while(true) {
            var batch=repo.watchPage(user,after,50); if(batch.isEmpty()) break;
            var codes=batch.stream().map(r->r.get("fund_code").toString()).toList();
            JsonNode result=client.coverage(codes);
            for(JsonNode row:result.path("items")) {
                checked++; counts.merge(row.path("status").asText(),1,Integer::sum);
                boolean match=(keyword==null||keyword.isBlank()||row.path("fund_name").asText().contains(keyword)||row.path("fund_code").asText().contains(keyword))
                        && (type==null||type.isBlank()||row.path("fund_type").asText().equals(type));
                if(match) { if(matched>=offset&&visible.size()<size) visible.add(Direction1dPolicy.view(row)); matched++; }
            }
            after=codes.get(codes.size()-1);
        }
        return Map.of("items",visible,"totalCount",matched,"checkedCount",checked,"statusCounts",counts,"page",page,"pageSize",size);
    }
    public Map<String,Object> current(String code) {
        UUID user=user(false); requireFund(user,code);
        var coverage=client.coverage(List.of(code)); var result=new LinkedHashMap<String,Object>();
        result.put("coverage",Direction1dPolicy.view(coverage.path("items").get(0)));
        result.put("window",Direction1dPolicy.view(coverage.path("window")));
        result.put("history",repo.history(user,code,LocalDate.of(2021,1,1),LocalDate.of(2026,12,31),1,1));
        result.put("enabled",repo.enabled(user)); return result;
    }
    public Map<String,Object> generate(String code) {
        UUID user=user(true); requireFund(user,code);
        if(!enabled||!forecastEnabled||!repo.enabled(user)) throw new IllegalArgumentException("SUBSCRIPTION_DISABLED");
        JsonNode status=checkedStatus(); JsonNode w=status.path("window");
        if(!"OPEN".equals(w.path("status").asText())) throw new IllegalArgumentException("MISSED_DEADLINE");
        var coverage=client.coverage(List.of(code)); UUID scope=repo.scope(user,LocalDate.parse(w.path("target_nav_date").asText()),
                List.of(Map.of("fund_code",code,"coverage",Direction1dPolicy.view(coverage.path("items").get(0)))));
        return process(user,scope,coverage.path("items").get(0),w);
    }
    public JsonNode checkedStatus() {
        JsonNode status=client.get("/status"); Instant now=repo.now();
        if(Math.abs(Duration.between(Instant.now(),now).toMillis())>5000
                || Math.abs(Duration.between(Direction1dPolicy.instant(status,"server_time"),now).toMillis())>5000
                || Math.abs(Duration.between(Direction1dPolicy.instant(status,"database_time"),now).toMillis())>5000)
            throw new IllegalArgumentException("CLOCK_SKEW");
        return status;
    }
    public Map<String,Object> process(UUID user,UUID scope,JsonNode coverage,JsonNode w) {
        String code=coverage.path("fund_code").asText(); LocalDate target=LocalDate.parse(w.path("target_nav_date").asText());
        UUID existing=repo.currentPublic(code,target);
        if(existing!=null) { repo.confirm(existing); repo.link(user,existing,scope); return Map.of("forecastId",existing,"status","PREDICTED"); }
        if(coverage.path("group_id").isNull() || coverage.path("group_id").isMissingNode()) {
            String reason=coverage.path("status").asText(); repo.attempt(scope,code,target,null,reason,Direction1dPolicy.view(coverage.path("reason_codes")));
            return Map.of("status",reason);
        }
        if(!"OPEN".equals(w.path("status").asText()) || !enabled || !forecastEnabled) {
            repo.attempt(scope,code,target,null,"MISSED_DEADLINE",List.of("MISSED_DEADLINE")); return Map.of("status","MISSED_DEADLINE");
        }
        for(JsonNode reason:coverage.path("reason_codes")) {
            if("MODEL_NOT_ACTIVE_FOR_WINDOW".equals(reason.asText()) || "MODEL_UNAVAILABLE".equals(reason.asText())) {
                repo.attempt(scope,code,target,null,"MODEL_UNAVAILABLE",Direction1dPolicy.view(coverage.path("reason_codes")));
                return Map.of("status","MODEL_UNAVAILABLE","reason",reason.asText());
            }
        }
        JsonNode task=client.post("/forecast-jobs",Map.of("fund_code",code)); UUID job=UUID.fromString(task.path("job_id").asText());
        JsonNode result=client.get("/forecast-jobs/"+job); String state=result.path("state").asText();
        if("SUCCEEDED".equals(state)) {
            JsonNode payload=result.path("result");
            UUID id=repo.archive(job,payload.path("payload_json").asText(),payload.path("content_hash").asText(),code);
            repo.link(user,id,scope); repo.attempt(scope,code,target,job,"PREDICTED",List.of());
            return Map.of("forecastId",id,"status","PREDICTED");
        }
        String reason=result.path("result").path("reason").asText(state);
        if("FAILED".equals(state)) state=switch(reason) {
            case "DATA_PENDING" -> "WAITING_DATA";
            case "MODEL_PENDING","MODEL_NOT_ACTIVE_FOR_WINDOW","MODEL_UNAVAILABLE" -> "MODEL_UNAVAILABLE";
            default -> "FAILED";
        };
        repo.attempt(scope,code,target,job,state,List.of(reason)); return Map.of("jobId",job,"status",state,"reason",reason);
    }
    public Map<String,Object> history(String code,LocalDate start,LocalDate end,int page,int size) {
        validatePage(page,size); if(start.isAfter(end)) throw new IllegalArgumentException("INVALID_RANGE");
        return repo.history(user(false),code,start,end,page,size);
    }
    public Map<String,Object> history(String code,LocalDate start,LocalDate end,int page,int size,LocalDate beforeDate,UUID beforeId,String branch,String assessment) {
        validatePage(page,size);
        if(start.isAfter(end) || (beforeDate==null)!=(beforeId==null)
                || !Set.of("","FIXED","WEEKLY","ALWAYS_UP","ALWAYS_NON_UP","INITIAL_MAJORITY","MOMENTUM").contains(branch)
                || !Set.of("","ASSESSED","PENDING").contains(assessment)) throw new IllegalArgumentException("INVALID_RANGE");
        return repo.history(user(false),code,start,end,page,size,beforeDate,beforeId,branch,assessment);
    }
    public Map<String,Object> detail(UUID id) { return repo.detail(user(false),id); }
    public Map<String,Object> metrics() { return Map.of("branches",repo.metrics(user(false)),"labelBasis","FIRST_OBSERVED",
            "observationNote","少于20个不同目标日属于很短观察期；未到期不计对错。","modelReleased",false); }
    private void requireFund(UUID user,String code) {
        if(!code.matches("[0-9]{6}")) throw new IllegalArgumentException("INVALID_FUND_CODE");
        if(!repo.follows(user,code)) throw new WatchlistRequiredException();
    }
    private void validatePage(int page,int size) { if(page<1||page>100000||size<1||size>100) throw new IllegalArgumentException("INVALID_PAGE"); }
}
