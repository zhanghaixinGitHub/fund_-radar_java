package com.fundradar.core.direction1d;

import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.util.*;

/** 同步中心和日常自动预测共用留档链路；按基金统计，多个账号关注不重复计算基金只数。 */
@Service
public class Direction1dBatchService {
    private final Direction1dRepository repo;
    private final Direction1dService service;
    private final Direction1dClient client;

    public Direction1dBatchService(Direction1dRepository repo,Direction1dService service,Direction1dClient client) {
        this.repo=repo; this.service=service; this.client=client;
    }

    public List<String> fundCodes(String after) { return repo.predictionFundCodes(after); }

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
        Map<String,Object> result=Map.of("status","NO_LONGER_FOLLOWED");
        do {
            var users=repo.predictionUsers(code,after);
            if(users.isEmpty()) break;
            for(UUID user:users) {
                UUID scope=repo.scope(user,target,List.of(Map.of("fund_code",code,"coverage",Direction1dPolicy.view(coverage))));
                result=service.process(user,scope,coverage,w);
                if("PREDICTED".equals(result.get("status")) && !Boolean.TRUE.equals(result.get("reused"))) created=true;
            }
            after=users.get(users.size()-1);
        } while(true);
        if("PREDICTED".equals(result.get("status"))) return Map.of("status","PREDICTED","reused",!created);
        return result;
    }
}
