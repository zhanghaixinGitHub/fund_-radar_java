package com.fundradar.core.direction1d;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/** Java权威档案：先提交预测，再新事务回读；所有个人查询必须携带认证用户编号。 */
@Repository
public class Direction1dRepository {
    private final JdbcClient db; private final ObjectMapper json; private final TransactionTemplate tx;
    public Direction1dRepository(JdbcClient db,ObjectMapper json,PlatformTransactionManager manager) {
        this.db=db; this.json=json; tx=new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public String encode(Object value) { try { return json.writeValueAsString(value); } catch(Exception e) { throw new IllegalStateException(e); } }
    public JsonNode decode(String value) { try { return json.readTree(value); } catch(Exception e) { throw new IllegalStateException("档案原文无法读取",e); } }
    public Instant now() { return db.sql("SELECT clock_timestamp()").query((r,n)->r.getTimestamp(1).toInstant()).single(); }
    public boolean enabled(UUID user) {
        return db.sql("SELECT EXISTS(SELECT 1 FROM direction_1d_subscription WHERE user_id=:u AND enabled)").param("u",user).query(Boolean.class).single();
    }
    public void subscribe(UUID user,boolean enabled) {
        db.sql("""
          INSERT INTO direction_1d_subscription(user_id,enabled,enabled_at,disabled_at)
          VALUES(:u,:e,CASE WHEN :e THEN clock_timestamp() END,CASE WHEN NOT :e THEN clock_timestamp() END)
          ON CONFLICT(user_id) DO UPDATE SET enabled=:e,updated_at=clock_timestamp(),
          enabled_at=CASE WHEN :e THEN clock_timestamp() ELSE direction_1d_subscription.enabled_at END,
          disabled_at=CASE WHEN NOT :e THEN clock_timestamp() ELSE direction_1d_subscription.disabled_at END
          """).param("u",user).param("e",enabled).update();
    }
    public List<UUID> users(UUID after) {
        return db.sql("""
          SELECT s.user_id FROM direction_1d_subscription s JOIN user_account u USING(user_id)
          WHERE s.enabled AND u.status='ACTIVE' AND (:after::uuid IS NULL OR s.user_id>:after)
            AND EXISTS(SELECT 1 FROM system_role r WHERE r.role_code=u.role AND r.status='ACTIVE')
            AND (SELECT count(*) FROM role_permission p WHERE p.role_code=u.role
              AND p.permission_code IN ('FUND_READ','WATCHLIST_SELF_READ','WATCHLIST_SELF_WRITE'))=3
          ORDER BY s.user_id LIMIT 50
          """).param("after",after).query(UUID.class).list();
    }
    public List<Map<String,Object>> watchPage(UUID user,String after,int size) {
        return db.sql("""
          SELECT fund_code,fund_type FROM watchlist_item WHERE user_id=:u AND fund_code>:after ORDER BY fund_code LIMIT :size
          """).param("u",user).param("after",after==null?"":after).param("size",Math.min(100,size)).query().listOfRows();
    }
    public boolean follows(UUID user,String code) {
        return db.sql("SELECT EXISTS(SELECT 1 FROM watchlist_item WHERE user_id=:u AND fund_code=:c)")
                .param("u",user).param("c",code).query(Boolean.class).single();
    }
    public UUID scope(UUID user,LocalDate target,List<Map<String,Object>> items) {
        UUID id=UUID.randomUUID(); String raw=encode(items);
        db.sql("""
          INSERT INTO direction_1d_scope_snapshot(snapshot_id,user_id,target_nav_date,items,scope_hash)
          VALUES(:id,:u,:target,CAST(:items AS jsonb),:hash)
          """).param("id",id).param("u",user).param("target",target).param("items",raw).param("hash",Direction1dPolicy.hash(raw)).update();
        return id;
    }
    public void attempt(UUID scope,String code,LocalDate target,UUID job,String status,Object reasons) {
        db.sql("""
          INSERT INTO direction_1d_attempt(attempt_id,scope_snapshot_id,fund_code,target_nav_date,source_job_id,status,reasons,next_retry_at,trace_id)
          VALUES(:id,:scope,:code,:target,:job,:status,CAST(:reasons AS jsonb),clock_timestamp()+interval '30 minutes',:trace)
          """).param("id",UUID.randomUUID()).param("scope",scope).param("code",code).param("target",target).param("job",job)
                .param("status",status).param("reasons",encode(reasons)).param("trace",com.fundradar.core.common.trace.TraceContext.getTraceId()).update();
    }
    public UUID currentPublic(String code,LocalDate target) {
        return db.sql("SELECT forecast_id FROM direction_1d_forecast WHERE fund_code=:code AND target_nav_date=:target ORDER BY stored_at LIMIT 1")
                .param("code",code).param("target",target).query(UUID.class).optional().orElse(null);
    }
    public UUID archive(UUID job,String raw,String hash,String expectedCode) {
        JsonNode p=Direction1dPolicy.validate(json,raw,hash,expectedCode,now());
        UUID saved=tx.execute(ignored->{
            db.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key,721109))").param("key",p.path("task_key").asText()).query((r,n)->0).list();
            UUID existing=currentPublic(expectedCode,LocalDate.parse(p.path("target_nav_date").asText()));
            if(existing!=null) return existing;
            UUID id=UUID.randomUUID();
            db.sql("""
              INSERT INTO direction_1d_forecast(forecast_id,source_job_id,protocol,cohort_id,fund_code,base_nav_date,target_nav_date,
                calendar_version,window_open_at,deadline_at,payload_json,payload,input_hash,content_hash,generated_at)
              VALUES(:id,:job,:protocol,:cohort,:code,:base,:target,:calendar,:open,:deadline,:raw,CAST(:raw AS jsonb),:input,:hash,:generated)
              """).param("id",id).param("job",job).param("protocol",Direction1dPolicy.PROTOCOL).param("cohort",p.path("cohort_id").asText())
                    .param("code",expectedCode).param("base",LocalDate.parse(p.path("base_nav_date").asText()))
                    .param("target",LocalDate.parse(p.path("target_nav_date").asText())).param("calendar",p.path("calendar_version").asText())
                    .param("open",Timestamp.from(Direction1dPolicy.instant(p,"window_open_at")))
                    .param("deadline",Timestamp.from(Direction1dPolicy.instant(p,"deadline_at"))).param("raw",raw)
                    .param("input",p.path("input_hash").asText()).param("hash",hash)
                    .param("generated",Timestamp.from(Direction1dPolicy.instant(p,"generated_at"))).update();
            for(JsonNode b:p.path("branches")) saveScore(id,b,false);
            for(JsonNode b:p.path("baselines")) saveScore(id,b,true);
            return id;
        });
        confirm(saved); return saved;
    }
    private void saveScore(UUID id,JsonNode b,boolean baseline) {
        db.sql("""
          INSERT INTO direction_1d_forecast_score(forecast_id,branch_id,model_id,model_hash,score,predicted_direction,status,train_as_of)
          VALUES(:id,:branch,:model,:hash,:score,:direction,:status,:train)
          """).param("id",id).param("branch",b.path("branch_id").asText())
                .param("model",baseline||b.path("model_id").isNull()?null:UUID.fromString(b.path("model_id").asText()))
                .param("hash",baseline?null:b.path("model_hash").asText(null))
                .param("score",baseline||b.path("score").isNull()?null:b.path("score").asDouble())
                .param("direction",b.path("predicted_direction").asText(null))
                .param("status",baseline?(b.path("predicted_direction").isNull()?"UNAVAILABLE":"AVAILABLE"):b.path("status").asText())
                .param("train",b.has("train_as_of")?Timestamp.from(Direction1dPolicy.instant(b,"train_as_of")):null).update();
    }
    /** 此方法在archive事务完成后执行，确认时读取的原始预测已真实提交。 */
    public void confirm(UUID id) {
        tx.executeWithoutResult(ignored->{
            var row=db.sql("SELECT payload_json,content_hash,generated_at,stored_at,deadline_at FROM direction_1d_forecast WHERE forecast_id=:id")
                    .param("id",id).query((r,n)->Map.of("raw",r.getString(1),"hash",r.getString(2),"generated",r.getTimestamp(3).toInstant(),
                            "stored",r.getTimestamp(4).toInstant(),"deadline",r.getTimestamp(5).toInstant())).single();
            Instant verified=now();
            String status=Direction1dPolicy.hash((String)row.get("raw")).equals(row.get("hash"))
                    ?Direction1dPolicy.receipt((Instant)row.get("generated"),(Instant)row.get("stored"),verified,(Instant)row.get("deadline")):"HASH_MISMATCH";
            db.sql("""
              INSERT INTO direction_1d_forecast_receipt(forecast_id,receipt_verified_at,content_hash,status)
              VALUES(:id,:at,:hash,:status) ON CONFLICT DO NOTHING
              """).param("id",id).param("at",Timestamp.from(verified)).param("hash",row.get("hash")).param("status",status).update();
        });
    }
    public void link(UUID user,UUID forecast,UUID scope) {
        // 用户新增关注只在真实截止前关联；取消关注后不会产生新关系。
        db.sql("""
          INSERT INTO direction_1d_user_forecast(user_id,forecast_id,scope_snapshot_id)
          SELECT :u,f.forecast_id,:scope FROM direction_1d_forecast f
          WHERE f.forecast_id=:id AND clock_timestamp()<f.deadline_at
            AND EXISTS(SELECT 1 FROM watchlist_item w WHERE w.user_id=:u AND w.fund_code=f.fund_code)
            AND EXISTS(SELECT 1 FROM direction_1d_subscription s WHERE s.user_id=:u AND s.enabled)
          ON CONFLICT DO NOTHING
          """).param("u",user).param("id",forecast).param("scope",scope).update();
    }
    public List<Map<String,Object>> reviewPage(UUID after) {
        return db.sql("""
          SELECT forecast_id,source_job_id FROM direction_1d_forecast
          WHERE target_nav_date<=(clock_timestamp() AT TIME ZONE 'Asia/Shanghai')::date
            AND (:after::uuid IS NULL OR forecast_id>:after) ORDER BY forecast_id LIMIT 50
          """).param("after",after).query().listOfRows();
    }
    public void outcome(UUID id,JsonNode label) {
        JsonNode p=label.path("payload");
        if(!"AVAILABLE".equals(p.path("status").asText())) return;
        tx.executeWithoutResult(ignored->{
            db.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key,721110))").param("key",id.toString()).query((r,n)->0).list();
            JsonNode original=decode(db.sql("SELECT payload_json FROM direction_1d_forecast WHERE forecast_id=:id").param("id",id).query(String.class).single());
            Direction1dPolicy.validateLabel(json,label,original,now());
            var a=new java.math.BigDecimal(p.path("base_unit_nav").asText()); var b=new java.math.BigDecimal(p.path("target_unit_nav").asText());
            if(a.signum()<=0||b.signum()<=0 || p.path("y").asInt(-1)!=(b.compareTo(a)>0?1:0)) throw new IllegalArgumentException("INVALID_LABEL");
            db.sql("""
              INSERT INTO direction_1d_outcome(forecast_id,revision_no,label_snapshot_id,label_hash,payload,base_unit_nav,target_unit_nav,
                y,actual_direction,nav_return,label_observed_at,revision_reason)
              SELECT :id,COALESCE(MAX(revision_no),0)+1,:snapshot,:hash,CAST(:payload AS jsonb),:a,:b,:y,:direction,:return,:observed,
                CASE WHEN MAX(revision_no) IS NULL THEN 'FIRST_OBSERVED' ELSE 'SOURCE_REVISION' END
              FROM direction_1d_outcome WHERE forecast_id=:id ON CONFLICT(forecast_id,label_hash) DO NOTHING
              """).param("id",id).param("snapshot",UUID.fromString(label.path("snapshot_id").asText()))
                    .param("hash",label.path("content_hash").asText()).param("payload",encode(p)).param("a",a).param("b",b)
                    .param("y",p.path("y").asInt()).param("direction",p.path("actual_direction").asText())
                    .param("return",new java.math.BigDecimal(p.path("nav_return").asText()))
                    .param("observed",Timestamp.from(Direction1dPolicy.instant(p,"label_observed_at"))).update();
        });
    }
    public Map<String,Object> detail(UUID user,UUID id) {
        var row=db.sql("""
          SELECT f.forecast_id,f.payload_json,f.content_hash,f.stored_at,r.receipt_verified_at,r.status,u.linked_at,
            COALESCE((SELECT jsonb_agg(o.payload ORDER BY o.revision_no) FROM direction_1d_outcome o
              WHERE o.forecast_id=f.forecast_id),'[]'::jsonb)::text AS outcomes_json
          FROM direction_1d_forecast f JOIN direction_1d_user_forecast u USING(forecast_id)
          LEFT JOIN direction_1d_forecast_receipt r USING(forecast_id) WHERE f.forecast_id=:id AND u.user_id=:u
          """).param("id",id).param("u",user).query().listOfRows().stream().findFirst().orElseThrow(()->new NoSuchElementException("记录不属于本人"));
        return render(row);
    }
    private Map<String,Object> render(Map<String,Object> row) {
        UUID id=(UUID)row.get("forecast_id"); String raw=(String)row.get("payload_json");
        if(!Direction1dPolicy.hash(raw).equals(row.get("content_hash"))) return Map.of("forecastId",id,"status","EVIDENCE_CORRUPTED");
        JsonNode payload=decode(raw);
        if(payload.has("expires_at") && !Direction1dPolicy.instant(payload,"expires_at").isAfter(now()))
            return Map.of("forecastId",id,"status","EVIDENCE_EXPIRED");
        Map<String,Object> result=new LinkedHashMap<>(); result.put("forecastId",id); result.put("forecast",Direction1dPolicy.view(payload));
        result.put("storedAt",row.get("stored_at")); result.put("receiptVerifiedAt",row.get("receipt_verified_at"));
        result.put("receiptStatus",row.get("status")); result.put("linkedAt",row.get("linked_at"));
        result.put("outcomes",Direction1dPolicy.view(decode((String)row.get("outcomes_json"))));
        return result;
    }
    public Map<String,Object> history(UUID user,String code,LocalDate start,LocalDate end,int page,int size) {
        return history(user,code,start,end,page,size,null,null,"","");
    }
    /** 日期+编号游标固定翻页边界，期间新增记录不会挤动后续页；旧page参数继续兼容详情。 */
    public Map<String,Object> history(UUID user,String code,LocalDate start,LocalDate end,int page,int size,
            LocalDate beforeDate,UUID beforeId,String branch,String assessment) {
        String condition=" FROM direction_1d_user_forecast u JOIN direction_1d_forecast f USING(forecast_id) WHERE u.user_id=:u AND (:code='' OR f.fund_code=:code) AND f.target_nav_date BETWEEN :start AND :end";
        condition+=" AND (:branch='' OR EXISTS(SELECT 1 FROM direction_1d_forecast_score b WHERE b.forecast_id=f.forecast_id AND b.branch_id=:branch AND b.status='AVAILABLE'))"
                +" AND (:assessment='' OR (:assessment='ASSESSED')=EXISTS(SELECT 1 FROM direction_1d_outcome o WHERE o.forecast_id=f.forecast_id))";
        String select="SELECT f.forecast_id,f.payload_json,f.content_hash,f.stored_at,u.linked_at,"
                +"(SELECT receipt_verified_at FROM direction_1d_forecast_receipt WHERE forecast_id=f.forecast_id) AS receipt_verified_at,"
                +"(SELECT status FROM direction_1d_forecast_receipt WHERE forecast_id=f.forecast_id) AS status,"
                +"COALESCE((SELECT jsonb_agg(o.payload ORDER BY o.revision_no) FROM direction_1d_outcome o WHERE o.forecast_id=f.forecast_id),'[]'::jsonb)::text AS outcomes_json";
        var q=db.sql(select+",f.target_nav_date"+condition+" AND (:beforeDate::date IS NULL OR (f.target_nav_date,f.forecast_id)<(:beforeDate,:beforeId)) ORDER BY f.target_nav_date DESC,f.forecast_id DESC LIMIT :size OFFSET :offset")
                .param("u",user).param("code",code==null?"":code).param("start",start).param("end",end).param("size",size)
                .param("branch",branch).param("assessment",assessment).param("beforeDate",beforeDate).param("beforeId",beforeId)
                .param("offset",beforeDate==null?(long)(page-1)*size:0L);
        var rows=q.query().listOfRows();
        long count=db.sql("SELECT count(*)"+condition).param("u",user).param("code",code==null?"":code).param("start",start).param("end",end)
                .param("branch",branch).param("assessment",assessment).query(Long.class).single();
        var out=new LinkedHashMap<String,Object>();out.put("items",rows.stream().map(this::render).toList());out.put("totalCount",count);out.put("page",page);out.put("pageSize",size);
        if(!rows.isEmpty()){var last=rows.get(rows.size()-1);out.put("nextCursor",Map.of("beforeDate",last.get("target_nav_date"),"beforeId",last.get("forecast_id")));}
        return out;
    }
    public List<Map<String,Object>> metrics(UUID user) {
        return db.sql("""
          SELECT s.branch_id,count(*) FILTER(WHERE r.status='VERIFIED' AND o.y IS NOT NULL) AS assessed_count,
            count(*) FILTER(WHERE r.status='VERIFIED' AND o.y IS NOT NULL AND (s.predicted_direction='UP')=(o.y=1)) AS correct_count,
            count(DISTINCT f.target_nav_date) FILTER(WHERE r.status='VERIFIED' AND o.y IS NOT NULL) AS distinct_target_dates,
            count(*) FILTER(WHERE o.y IS NULL) AS pending_count,
            count(*) FILTER(WHERE o.actual_direction='FLAT') AS flat_count,
            count(*) FILTER(WHERE r.status<>'VERIFIED' OR r.status IS NULL) AS late_or_unverified_count
          FROM direction_1d_user_forecast u JOIN direction_1d_forecast f USING(forecast_id)
          JOIN direction_1d_forecast_score s USING(forecast_id) LEFT JOIN direction_1d_forecast_receipt r USING(forecast_id)
          LEFT JOIN direction_1d_outcome o ON o.forecast_id=f.forecast_id AND o.revision_no=1
          WHERE u.user_id=:u AND s.status='AVAILABLE' GROUP BY s.branch_id ORDER BY s.branch_id
          """).param("u",user).query().listOfRows();
    }
    public void health(String state,int checked,int failed,String message,boolean finish) {
        db.sql("""
          INSERT INTO direction_1d_task_health(task_name,state,started_at,finished_at,checked_count,failed_count,message,next_run_at)
          VALUES('direction-1d',:state,clock_timestamp(),CASE WHEN :finish THEN clock_timestamp() END,:checked,:failed,:message,clock_timestamp()+interval '30 minutes')
          ON CONFLICT(task_name) DO UPDATE SET state=:state,checked_count=:checked,failed_count=:failed,message=:message,
            started_at=CASE WHEN NOT :finish THEN clock_timestamp() ELSE direction_1d_task_health.started_at END,
            finished_at=CASE WHEN :finish THEN clock_timestamp() ELSE direction_1d_task_health.finished_at END,
            next_run_at=clock_timestamp()+interval '30 minutes'
          """).param("state",state).param("checked",checked).param("failed",failed).param("message",message).param("finish",finish).update();
    }
    public List<Map<String,Object>> health() { return db.sql("SELECT * FROM direction_1d_task_health").query().listOfRows(); }
}
