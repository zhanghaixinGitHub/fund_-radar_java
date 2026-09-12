package com.fundradar.core.direction1d;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.time.LocalDate;
import java.util.*;

/** 独立30分钟后台；先核对旧原文，再组织当前范围，最后请求本周固定训练。 */
@Component
@ConditionalOnProperty(name="direction1d.enabled",havingValue="true")
public class Direction1dScheduler {
    private static final Logger LOG=LoggerFactory.getLogger(Direction1dScheduler.class);
    private final DataSource dataSource; private final Direction1dRepository repo; private final Direction1dService service;
    private final Direction1dClient client; private final boolean review; private final boolean training;
    public Direction1dScheduler(DataSource dataSource,Direction1dRepository repo,Direction1dService service,Direction1dClient client,
        @Value("${direction1d.review-enabled:true}") boolean review,@Value("${direction1d.training-enabled:true}") boolean training) {
        this.dataSource=dataSource; this.repo=repo; this.service=service; this.client=client; this.review=review; this.training=training;
    }
    @Scheduled(scheduler="direction1dTaskScheduler",fixedDelayString="${direction1d.fixed-delay:PT30M}",initialDelayString="${direction1d.initial-delay:PT10S}")
    public void tick() {
        try(var c=dataSource.getConnection();var lock=c.prepareStatement("SELECT pg_try_advisory_lock(721111,1)")) {
            var rs=lock.executeQuery();rs.next();if(!rs.getBoolean(1))return;
            try { run(); } finally { try(var release=c.prepareStatement("SELECT pg_advisory_unlock(721111,1)")){release.execute();} }
        } catch(Exception error) {
            LOG.error("Direction1dScheduler.tick   >>>   后台检查失败，旧原文保留",error);
            repo.health("FAILED",0,1,"后台检查失败；停止新增预测，保留历史。",true);
        }
    }
    public void run() {
        repo.health("RUNNING",0,0,"正在检查到期答案和本人订阅范围。",false);
        int checked=0,failed=0; UUID after=null;
        if(review) while(true) {
            var batch=repo.reviewPage(after); if(batch.isEmpty())break;
            for(var r:batch) {
                UUID id=(UUID)r.get("forecast_id"),job=(UUID)r.get("source_job_id");
                try {
                    JsonNode label=client.get("/labels/"+job);
                    if(label.has("payload")) {
                        repo.outcome(id,label); // 此方法返回时核对事务已提交；随后才能授权Python学习。
                        if(label.path("payload").path("training_eligible").asBoolean(false))
                            client.post("/labels/"+job+"/assessed",Map.of("label_hash",label.path("content_hash").asText()));
                    }
                } catch(Exception error) {failed++;LOG.error("Direction1dScheduler.run   >>>   forecastId={}, 到期核对失败",id,error);}
            }
            after=(UUID)batch.get(batch.size()-1).get("forecast_id");
        }
        JsonNode state=service.checkedStatus(),w=state.path("window"); LocalDate target=LocalDate.parse(w.path("target_nav_date").asText());
        after=null;
        while(true) {
            var users=repo.users(after); if(users.isEmpty())break;
            for(UUID user:users) {
                String codeAfter="";
                while(true) {
                    var batch=repo.watchPage(user,codeAfter,50); if(batch.isEmpty())break;
                    var codes=batch.stream().map(r->r.get("fund_code").toString()).toList();
                    JsonNode coverage=client.coverage(codes); UUID scope=repo.scope(user,target,batch);
                    for(JsonNode row:coverage.path("items")) {
                        try {var result=service.process(user,scope,row,w);checked++;if("FAILED".equals(result.get("status")))failed++;}
                        catch(Exception error){failed++;repo.attempt(scope,row.path("fund_code").asText(),target,null,"FAILED",List.of("INTERNAL_FAILURE"));
                            LOG.error("Direction1dScheduler.run   >>>   fundCode={}, 当前预测检查失败",row.path("fund_code").asText(),error);}
                    }
                    codeAfter=codes.get(codes.size()-1);
                }
            }
            after=users.get(users.size()-1);
        }
        if(training&&failed==0)client.post("/training-jobs",Map.of());
        repo.health(failed==0?"SUCCEEDED":"PARTIAL",checked,failed,"独立1日检查完成；未到期不计对错。",true);
        LOG.info("Direction1dScheduler.run   >>>   target={}, checked={}, failed={}",target,checked,failed);
    }
}
