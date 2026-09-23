package com.fundradar.core.prediction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fundradar.core.advice.AdviceScheduler;
import com.fundradar.core.direction1d.Direction1dBatchService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.sql.DataSource;
import java.util.*;

/** 唯一的多周期自动入口。原预测原文复用，失败项可在下一轮重试；从不创建交易。 */
@Service
public class MultiPredictionPipeline {
    private static final Logger LOG=LoggerFactory.getLogger(MultiPredictionPipeline.class);
    private final MultiPredictionClient client; private final MultiPredictionService service;
    private final Direction1dBatchService scope; private final JdbcClient db; private final DataSource datasource;
    private final ObjectProvider<AdviceScheduler> advice;
    public MultiPredictionPipeline(MultiPredictionClient client,MultiPredictionService service,Direction1dBatchService scope,
                                   JdbcClient db,DataSource datasource,ObjectProvider<AdviceScheduler> advice) {
        this.client=client;this.service=service;this.scope=scope;this.db=db;this.datasource=datasource;this.advice=advice;
    }
    @Scheduled(scheduler="adviceTaskScheduler",fixedDelayString="${prediction.multi.fixed-delay:PT30M}",
            initialDelayString="${prediction.multi.initial-delay:PT40S}")
    public void tick() {
        try(var connection=datasource.getConnection()) {
            try(var statement=connection.prepareStatement("SELECT pg_try_advisory_lock(721109,1)")) {
                var result=statement.executeQuery();result.next();if(!result.getBoolean(1)) return;
            }
            try {
                client.post("/maintenance",Map.of());
                String after="";int funds=0,items=0,failed=0;
                while(true) {
                    var codes=scope.fundCodes(after); if(codes.isEmpty()) break;
                    JsonNode task=client.post("/batches",Map.of("fundCodes",codes,"requestKey",UUID.randomUUID()));
                    String id=task.path("taskId").asText();
                    for(int poll=0;poll<600 && Set.of("QUEUED","RUNNING","INTERRUPTED").contains(task.path("status").asText());poll++) {
                        Thread.sleep(500);task=client.get("/batches/"+id);
                    }
                    if(task.path("pendingItems").asInt()>0) throw new IllegalStateException("预测仍运行，保留任务继续恢复："+id);
                    archive(UUID.fromString(id),false);
                    funds+=task.path("fundCount").asInt();items+=task.path("plannedItems").asInt();failed+=task.path("failedItems").asInt();
                    after=codes.get(codes.size()-1);
                }
                var worker=advice.getIfAvailable();if(worker!=null) worker.tick();
                client.post("/maintenance",Map.of());
                LOG.info("MultiPredictionPipeline.tick   >>> funds={}, items={}, failedItems={}",funds,items,failed);
            } finally { try(var statement=connection.prepareStatement("SELECT pg_advisory_unlock(721109,1)")) {statement.execute();} }
        } catch(InterruptedException error) {Thread.currentThread().interrupt();LOG.warn("MultiPredictionPipeline.tick   >>> interrupted",error);
        } catch(Exception error) {LOG.error("MultiPredictionPipeline.tick   >>> pipeline failed; committed evidence preserved",error);}
    }
    /** 只按服务器查出的当前关注关系建立引用，回调不能指定任何私人账户。 */
    public Map<String,Object> archive(UUID taskId,boolean generateAdvice) {
        var task=client.get("/batches/"+taskId);
        if(task.path("pendingItems").asInt()>0) throw new IllegalArgumentException("公共预测尚未保存完成");
        var codes=new TreeSet<String>();task.path("items").forEach(i->codes.add(i.path("fundCode").asText()));
        int linked=0,failed=0;
        for(String code:codes) {
            UUID after=null;
            while(true) {
                var users=db.sql("SELECT user_id FROM watchlist_item WHERE fund_code=:c AND (CAST(:after AS uuid) IS NULL OR user_id>:after) ORDER BY user_id LIMIT 100")
                        .param("c",code).param("after",after,java.sql.Types.OTHER).query(UUID.class).list();
                if(users.isEmpty()) break;
                for(UUID user:users) {try {service.currentFor(user,code);linked++;}
                    catch(Exception error) {failed++;LOG.error("MultiPredictionPipeline.archive   >>> taskId={}, fund={}, archive failed",taskId,code,error);}}
                after=users.get(users.size()-1);
            }
        }
        if(generateAdvice) {var worker=advice.getIfAvailable();if(worker!=null) worker.tick();}
        return Map.of("taskId",taskId,"fundCount",codes.size(),"linkedScopes",linked,"failed",failed);
    }
}
