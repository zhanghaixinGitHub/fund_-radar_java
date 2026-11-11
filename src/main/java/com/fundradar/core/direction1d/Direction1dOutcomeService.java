package com.fundradar.core.direction1d;

import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

/** 每日到期核对独立于训练；只读既有净值，沿用原答案校验及追加保存。 */
@Service
public class Direction1dOutcomeService {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(Direction1dOutcomeService.class);
    private final Direction1dRepository repo;
    private final Direction1dClient client;
    private final Clock clock;
    public Direction1dOutcomeService(Direction1dRepository repo,Direction1dClient client,Clock clock) {
        this.repo=repo;this.client=client;this.clock=clock;
    }
    public Map<String,Object> status(UUID owner,LocalDate dueBefore) {
        return repo.outcomeSummary(owner,dueBefore);
    }
    public Map<String,Object> archiveStatus(UUID owner,LocalDate dueBefore) {
        return repo.outcomeSummary(owner,dueBefore,true);
    }
    public List<String> archiveCodes(UUID owner) { return repo.archivedFundCodes(owner); }
    public Map<String,Object> reconcile(UUID owner,LocalDate dueBefore,UUID after) {
        return reconcile(owner,dueBefore,after,false);
    }
    public Map<String,Object> reconcile(UUID owner,LocalDate dueBefore,UUID after,boolean archive) {
        if(dueBefore.isAfter(clock.instant().atZone(Direction1dPolicy.ZONE).toLocalDate()))
            throw new IllegalArgumentException("OUTCOME_FUTURE_CUTOFF");
        var rows=archive?repo.dueOutcomes(owner,dueBefore,after,true):repo.dueOutcomes(owner,dueBefore,after);
        int completed=0,created=0,pending=0,failed=0;
        var items=new ArrayList<Map<String,Object>>();
        for(var row:rows) {
            UUID id=(UUID)row.get("forecast_id");String code=row.get("fund_code").toString();
            String status="FAILED";
            try {
                // 保存前再次确认当前持仓，清仓后停止处理；不提交新预测或训练回执。
                if(!archive && (!repo.holds(owner,code)||!repo.predictionEligible(owner,code)))
                    throw new IllegalStateException("HOLDING_SCOPE_CHANGED");
                var label=client.get("/labels/"+row.get("source_job_id")+"?fetch_missing=false");
                if("AVAILABLE".equals(label.path("payload").path("status").asText())) {
                    repo.outcome(id,label);
                    if(!repo.hasVerifiedOutcome(id)) throw new IllegalStateException("OUTCOME_READBACK_MISSING");
                    status="SUCCEEDED";completed++;
                    if(!Boolean.TRUE.equals(row.get("has_outcome"))) created++;
                } else if(Set.of("PENDING_NAV","PENDING_TARGET").contains(label.path("status").asText())) {
                    status="PENDING_DATA";pending++;
                } else failed++;
            } catch(Exception error) {
                failed++;LOG.warn("一日原预测到期核对失败 forecastId={}",id,error);
            }
            repo.outcomeCheck(id,status);
            items.add(Map.of("forecastId",id,"fundCode",code,"status",status));
        }
        var result=new LinkedHashMap<String,Object>();
        result.put("dueBefore",dueBefore.toString());result.put("checked",rows.size());
        result.put("completed",completed);result.put("created",created);result.put("pending",pending);result.put("failed",failed);
        result.put("items",items);
        result.put("nextCursor",rows.size()==50?rows.get(rows.size()-1).get("forecast_id"):null);
        return result;
    }
}
