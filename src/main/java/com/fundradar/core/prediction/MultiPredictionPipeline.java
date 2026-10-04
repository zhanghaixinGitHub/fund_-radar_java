package com.fundradar.core.prediction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fundradar.core.advice.AdviceScheduler;
import com.fundradar.core.direction1d.Direction1dBatchService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.sql.DataSource;
import java.util.*;

/** 多周期手动编排与留档；不注册定时预测，一键同步由既有内部回调完成留档。 */
@Service
public class MultiPredictionPipeline {
    private static final Logger LOG=LoggerFactory.getLogger(MultiPredictionPipeline.class);
    private final MultiPredictionClient client; private final MultiPredictionService service;
    private final Direction1dBatchService scope; private final JdbcClient db; private final DataSource datasource;
    private final ObjectProvider<AdviceScheduler> advice;
    private final ObjectProvider<AutoModelService> automatic;
    public MultiPredictionPipeline(MultiPredictionClient client,MultiPredictionService service,Direction1dBatchService scope,
                                   JdbcClient db,DataSource datasource,ObjectProvider<AdviceScheduler> advice,ObjectProvider<AutoModelService> automatic) {
        this.client=client;this.service=service;this.scope=scope;this.db=db;this.datasource=datasource;this.advice=advice;this.automatic=automatic;
    }
    /** 保留显式调用兼容性；本方法不再由定时器触发。 */
    public void tick() {
        try(var connection=datasource.getConnection()) {
            try(var statement=connection.prepareStatement("SELECT pg_try_advisory_lock(721109,1)")) {
                var result=statement.executeQuery();result.next();if(!result.getBoolean(1)) return;
            }
            try {
                client.post("/maintenance",Map.of());
                var improvement=automatic.getIfAvailable();if(improvement!=null) improvement.check("MAINTENANCE");
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
                if(improvement!=null) {improvement.dispatch();improvement.confirmAdoption();}
                client.post("/maintenance",Map.of());
                LOG.info("MultiPredictionPipeline.tick   >>> funds={}, items={}, failedItems={}",funds,items,failed);
            } finally { try(var statement=connection.prepareStatement("SELECT pg_advisory_unlock(721109,1)")) {statement.execute();} }
        } catch(InterruptedException error) {Thread.currentThread().interrupt();LOG.warn("MultiPredictionPipeline.tick   >>> interrupted",error);
        } catch(Exception error) {LOG.error("MultiPredictionPipeline.tick   >>> pipeline failed; committed evidence preserved",error);}
    }
    /** 当前有效关注范围内最近两期的已保存成果；与本轮待生成项分开，不修改任何预测。 */
    public List<Map<String,Object>> savedResults() {
        var codes=new ArrayList<String>(); String after="";
        while(true) { var page=scope.fundCodes(after); if(page.isEmpty()) break;
            codes.addAll(page); after=page.get(page.size()-1); }
        if(codes.isEmpty()) return List.of();
        return db.sql("""
          WITH facts AS (
            SELECT f.fund_code,'T1' AS horizon,f.target_nav_date::text AS day
            FROM direction_1d_forecast f JOIN direction_1d_forecast_receipt r USING(forecast_id)
            WHERE f.fund_code IN (:codes) AND r.status='VERIFIED' AND r.content_hash=f.content_hash
              AND EXISTS(SELECT 1 FROM direction_1d_user_forecast u JOIN watchlist_item w
                ON w.user_id=u.user_id AND w.fund_code=f.fund_code WHERE u.forecast_id=f.forecast_id)
            UNION ALL
            SELECT l.fund_code,l.payload->>'horizonId',l.payload->>'startDate'
            FROM prediction_user_link l JOIN watchlist_item w ON w.user_id=l.user_id AND w.fund_code=l.fund_code
            WHERE l.fund_code IN (:codes) AND l.payload->>'startDate' IS NOT NULL
          ), counts AS (
            SELECT horizon,day,count(DISTINCT fund_code) AS count,
              dense_rank() OVER(PARTITION BY horizon ORDER BY day DESC) AS rank
            FROM facts GROUP BY horizon,day
          ) SELECT horizon AS "horizonId",day AS "targetDate",count FROM counts
            WHERE rank<=2 ORDER BY day DESC,horizon
          """).param("codes",codes).query().listOfRows();
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
        if(generateAdvice) {var improvement=automatic.getIfAvailable();if(improvement!=null) improvement.check("SYNC_COMPLETED");}
        return Map.of("taskId",taskId,"fundCount",codes.size(),"linkedScopes",linked,"failed",failed);
    }
}
