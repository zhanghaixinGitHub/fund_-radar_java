package com.fundradar.core.prediction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fundradar.core.advice.StrategyReplayEngine;
import com.fundradar.core.advice.StrategyResearchService;
import com.fundradar.core.auth.CurrentUserContext;
import com.fundradar.core.auth.PermissionCode;
import com.fundradar.core.direction1d.Direction1dPolicy;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** 复用Python周期与Java成交账本；专用有界线程不占用日常预测调度，无额外定时器。 */
@Service
public class AutoModelService {
    private static final Logger LOG=LoggerFactory.getLogger(AutoModelService.class);
    private final MultiPredictionClient client;
    private final StrategyResearchService replay;
    private final ObjectMapper json;
    private final JdbcClient db;
    private final com.fundradar.core.direction1d.Direction1dBatchService scope;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"auto-model-replay");t.setDaemon(true);return t;});
    private final AtomicBoolean running=new AtomicBoolean();
    private static final String ENGINE_HASH=engineHash();
    /** 对实际加载的字节码取指纹，阻止不同运行版本拼接同一周期结果。 */
    static String engineHash() {
        try {
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            var types=new ArrayList<Class<?>>();
            for(var root:List.of(StrategyReplayEngine.class,com.fundradar.core.advice.DecisionPolicyV2.class,PredictionDirectionContract.class)) {
                types.add(root);types.addAll(Arrays.asList(root.getDeclaredClasses()));
            }
            types.sort(Comparator.comparing(Class::getName));
            for(var type:types)
                try(var stream=type.getResourceAsStream("/"+type.getName().replace('.','/')+".class")) {
                    if(stream==null) throw new IllegalStateException("账本实现不可读");
                    digest.update(stream.readAllBytes());
                }
            for(var resource:List.of("/prediction-policy-v1.json","/prediction-policy-v2.json")) try(var policy=StrategyReplayEngine.class.getResourceAsStream(resource)) {
                if(policy==null) throw new IllegalStateException("决策配置不可读");
                digest.update(policy.readAllBytes());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch(Exception e) {throw new IllegalStateException("账本版本指纹计算失败",e);}
    }
    public AutoModelService(MultiPredictionClient client,StrategyResearchService replay,ObjectMapper json,JdbcClient db,
                            com.fundradar.core.direction1d.Direction1dBatchService scope) {
        this.client=client;this.replay=replay;this.json=json;this.db=db;this.scope=scope;
    }
    /** 由唯一维护入口及成功同步回调调用；后台范围从数据库取得，浏览器不可指定其他账户。 */
    public void check(String source) {
        try {
            var codes=new ArrayList<String>();String after="";
            while(true) {var page=scope.fundCodes(after);if(page.isEmpty()) break;codes.addAll(page);after=page.get(page.size()-1);}
            client.post("/auto/check",Map.of("fundCodes",codes,"source",source));
        }
        catch(Exception e) {LOG.error("AutoModelService.check   >>> auto check failed; daily predictions continue",e);}
        dispatch();
    }
    public void dispatch() {
        if(!running.compareAndSet(false,true)) return;
        worker.execute(()->{try {runReplay();} finally {running.set(false);}});
    }
    /** 一次仅领取一个周期，逐基金保存回执；崩溃后沿用冻结输入和首次结果。 */
    void runReplay() {
        try {
            var claim=client.post("/auto/replay/claim",Map.of());
            if(claim==null||claim.isNull()) return;
            String cycle=claim.path("cycleId").asText(),owner=claim.path("leaseOwner").asText();
            var config=json.treeToValue(claim.path("executionPolicy"),StrategyReplayEngine.Config.class);
            long started=System.nanoTime();
            for(var fund:claim.path("fundCodes")) {
                if(Thread.currentThread().isInterrupted()||System.nanoTime()-started>TimeUnit.SECONDS.toNanos(claim.path("maximumWorkerSeconds").asLong(3600))) return;
                String code=fund.asText(); ObjectNode result=json.createObjectNode();
                try {
                    var input=client.get("/auto/cycles/"+cycle+"/replay/"+code+"?owner="+owner);
                    result=replay.compareModels(input,config);
                    result.remove("inputSnapshot");
                    result.put("family",input.path("family").asText(code));
                    result.put("inputHash",input.path("inputHash").asText());
                    result.put("engineHash",ENGINE_HASH);
                    // 阶段回执保存账本结果和退出复盘，曲线不随每30分钟摘要反复返回。
                    result.path("comparisons").forEach(value->{if(value instanceof ObjectNode node) node.remove(List.of("curve","trades"));});
                } catch(Exception e) {
                    result.put("status","FAILED");
                    result.put("error",e instanceof MultiPredictionClient.PredictionServiceFailure failure?failure.detail().summary():"本基金扣费回放失败");
                    LOG.error("AutoModelService.runReplay   >>> cycleId={}, fund={}, failed",cycle,code,e);
                }
                client.post("/auto/cycles/"+cycle+"/replay/"+code,Map.of("leaseOwner",owner,"result",PredictionWebData.of(result)));
            }
        } catch(Exception e) {LOG.error("AutoModelService.runReplay   >>> saved checkpoints retained",e);}
    }
    private List<String> myFunds() {
        CurrentUserContext.requirePermission(PermissionCode.FUND_READ);
        UUID user=CurrentUserContext.requirePermission(PermissionCode.WATCHLIST_SELF_READ).userId();
        return db.sql("SELECT fund_code FROM watchlist_item WHERE user_id=:u ORDER BY fund_code").param("u",user).query(String.class).list();
    }
    public JsonNode summary() {return client.post("/auto/summary",Map.of("fundCodes",myFunds()));}
    public JsonNode effects() {return client.post("/auto/effects",Map.of("fundCodes",myFunds()));}
    public JsonNode cycles(String before,UUID beforeId) {
        CurrentUserContext.requirePermission(PermissionCode.RESEARCH_RUN_ADMIN);
        if(before!=null) java.time.Instant.parse(before);
        var result=(ObjectNode)client.get("/auto/cycles?limit=20"+(before==null?"":"&before="+java.net.URLEncoder.encode(before,java.nio.charset.StandardCharsets.UTF_8))
                +(beforeId==null?"":"&beforeId="+beforeId));
        result.put("runtimeEngineHash",ENGINE_HASH);return result;
    }
    public JsonNode cycle(UUID id) {CurrentUserContext.requirePermission(PermissionCode.RESEARCH_RUN_ADMIN);return client.get("/auto/cycles/"+id);}
    public JsonNode action(UUID id,String action) {
        CurrentUserContext.requirePermission(PermissionCode.RESEARCH_RUN_ADMIN);
        if(!Set.of("cancel","resume").contains(action)) throw new IllegalArgumentException("操作不正确");
        return client.post("/auto/cycles/"+id+"/"+action,Map.of());
    }
    /** 从已保存报告核对实际采用预测，不按当前路由给旧报告补版本；只回传公共标识和摘要hash。 */
    public void confirmAdoption() {
        // 只核验待采用清单，避免历史发布占满LIMIT后新版本永远取不到三周期回执。
        for(var pending:client.get("/auto/releases/pending")) {
        String release=pending.path("release_id").asText();
        var values=db.sql("""
          SELECT DISTINCT ON(p->>'horizonId') p->>'predictionId' prediction_id
          FROM portfolio_decision_report r,
            LATERAL jsonb_array_elements(r.payload->'predictionSnapshot'->'predictions') p
          WHERE r.generation_status='SUCCEEDED' AND p->>'releaseId'=:release
            AND EXISTS (SELECT 1 FROM jsonb_array_elements(r.payload->'modelRefs') ref
              WHERE ref->>'predictionId'=p->>'predictionId')
          ORDER BY p->>'horizonId',r.generated_at DESC LIMIT 3
          """).param("release",release).query().listOfRows();
            var ids=values.stream().map(row->row.get("prediction_id").toString()).toList();
            if(ids.size()!=3) continue;
            try {client.post("/auto/releases/"+release+"/receipt",Map.of("predictionIds",ids,"apiReadback",true,
                    "adviceReferenced",true,"referenceHash",Direction1dPolicy.hash(ids.toString())));}
            catch(Exception e) {LOG.info("AutoModelService.confirmAdoption   >>> releaseId={}, waiting for complete actual-use evidence",release);}
        }
    }
    @PreDestroy public void close() {worker.shutdownNow();}
}
