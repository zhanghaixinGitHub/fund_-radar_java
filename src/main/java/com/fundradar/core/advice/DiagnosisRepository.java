package com.fundradar.core.advice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fundradar.core.simulation.SimulationException;
import com.fundradar.core.simulation.SimulationTypes.Page;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import static com.fundradar.core.advice.DiagnosisTypes.*;

/** 诊断报告只追加；items为自由结构JSONB，哈希前统一按键序规范化，避免JSONB重排键序造成误判。 */
@Repository
public class DiagnosisRepository {
    private final JdbcClient db;
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true);
    public DiagnosisRepository(JdbcClient db) { this.db=db; }
    public String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch(Exception e) { throw new IllegalStateException("诊断报告序列化失败",e); }
    }
    private List<FactItem> decodeItems(String value) {
        try { return value==null ? null : json.readValue(value,new TypeReference<>() {}); }
        catch(Exception e) { throw new IllegalStateException("诊断报告解析失败",e); }
    }
    public String hash(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode(value).getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    public void lock(UUID user,String code) {
        db.sql("SELECT pg_advisory_xact_lock(721108,hashtext(:key))").param("key",user+":"+code).query((r,n)->0).list();
    }
    public boolean owns(UUID user,String code) {
        return db.sql("SELECT EXISTS(SELECT 1 FROM sim_position WHERE user_id=:user AND fund_code=:code) OR EXISTS(SELECT 1 FROM holding_diagnosis_report WHERE user_id=:user AND fund_code=:code)")
                .param("user",user).param("code",code).query(Boolean.class).single();
    }
    /** 批量摘要只覆盖当前持仓基金；清仓基金的历史经单基金接口读取。 */
    public List<Summary> latest(UUID user) {
        return db.sql("""
            SELECT DISTINCT ON(r.fund_code) r.* FROM holding_diagnosis_report r
            WHERE r.user_id=:user AND EXISTS(SELECT 1 FROM sim_position p WHERE p.user_id=r.user_id AND p.fund_code=r.fund_code)
            ORDER BY r.fund_code,r.generated_at DESC,r.report_id DESC
            """).param("user",user).query((r,n)->summary(r)).list();
    }
    public Summary latest(UUID user,String code) {
        return db.sql("SELECT * FROM holding_diagnosis_report r WHERE r.user_id=:user AND r.fund_code=:code ORDER BY r.generated_at DESC,r.report_id DESC LIMIT 1")
                .param("user",user).param("code",code).query((r,n)->summary(r)).optional().orElse(null);
    }
    /** 上一诊断日（不含当日）最新报告的逐项终判作为本次基线；排除当日版本以保证同日同输入幂等。 */
    public List<FactItem> baselineItems(UUID user,String code,LocalDate today) {
        return db.sql("SELECT items FROM holding_diagnosis_report WHERE user_id=:user AND fund_code=:code AND report_date<:today ORDER BY report_date DESC,generated_at DESC,report_id DESC LIMIT 1")
                .param("user",user).param("code",code).param("today",today).query((r,n)->decodeItems(r.getString(1))).optional().orElse(null);
    }
    /** 今日之前各诊断日的同类排名后25%标记，按日取最新版本；数据不足的日记null并中断连续统计。 */
    public List<RankDay> recentRankDays(UUID user,String code,LocalDate today,int limit) {
        return db.sql("""
            SELECT DISTINCT ON(r.report_date) r.report_date,
                CASE WHEN item->>'verdict'='INSUFFICIENT' OR item->'facts'->>'percentile' IS NULL THEN NULL
                     ELSE (item->'facts'->>'percentile')::numeric>0.75 END AS bottom
            FROM holding_diagnosis_report r,jsonb_array_elements(r.items) item
            WHERE r.user_id=:user AND r.fund_code=:code AND r.report_date<:today AND item->>'item'='SAME_TYPE_RANK'
            ORDER BY r.report_date DESC,r.generated_at DESC,r.report_id DESC LIMIT :limit
            """).param("user",user).param("code",code).param("today",today).param("limit",limit)
                .query((r,n)->new RankDay(r.getObject(1,LocalDate.class),r.getObject(2,Boolean.class))).list();
    }
    public Page<Summary> history(UUID user,String code,int page,int size) {
        var rows=db.sql("SELECT * FROM holding_diagnosis_report r WHERE r.user_id=:user AND r.fund_code=:code ORDER BY r.generated_at DESC,r.report_id DESC LIMIT :size OFFSET :offset")
                .param("user",user).param("code",code).param("size",size).param("offset",(long)(page-1)*size).query((r,n)->summary(r)).list();
        long count=db.sql("SELECT count(*) FROM holding_diagnosis_report WHERE user_id=:user AND fund_code=:code")
                .param("user",user).param("code",code).query(Long.class).single();
        return new Page<>(rows,page,size,count);
    }
    public Detail detail(UUID user,String code,UUID id) {
        return db.sql("SELECT * FROM holding_diagnosis_report r WHERE r.user_id=:user AND r.fund_code=:code AND r.report_id=:id")
                .param("user",user).param("code",code).param("id",id).query((r,n)-> {
                    List<FactItem> items=decodeItems(r.getString("items"));
                    if(!hash(items).equals(r.getString("content_hash"))) throw new IllegalStateException("原始诊断快照完整性校验失败");
                    return new Detail(summary(r),items);
                }).optional().orElseThrow(DiagnosisRepository::notFound);
    }
    public UUID insert(UUID user,LocalDate today,Instant now,String code,String name,String verdict,
                       LocalDate cutoff,List<FactItem> items,String fingerprint) {
        UUID id=UUID.randomUUID();
        db.sql("""
            INSERT INTO holding_diagnosis_report(report_id,user_id,fund_code,fund_name,report_date,generated_at,
                verdict,items,cutoff_date,fingerprint,content_hash)
            VALUES (:id,:user,:code,:name,:date,:now,:verdict,CAST(:items AS jsonb),:cutoff,:fingerprint,:hash)
            ON CONFLICT (user_id,fund_code,report_date,fingerprint) DO NOTHING
            """).param("id",id).param("user",user).param("code",code).param("name",name)
                .param("date",today).param("now",Timestamp.from(now)).param("verdict",verdict)
                .param("items",encode(items)).param("cutoff",cutoff,Types.DATE)
                .param("fingerprint",fingerprint).param("hash",hash(items)).update();
        return db.sql("SELECT report_id FROM holding_diagnosis_report WHERE user_id=:user AND fund_code=:code AND report_date=:date AND fingerprint=:fingerprint")
                .param("user",user).param("code",code).param("date",today).param("fingerprint",fingerprint).query(UUID.class).single();
    }
    private Summary summary(ResultSet r) throws SQLException {
        return new Summary(r.getObject("report_id",UUID.class),r.getString("fund_code"),r.getString("fund_name"),
                r.getObject("report_date",LocalDate.class),r.getTimestamp("generated_at").toInstant(),
                r.getString("verdict"),r.getObject("cutoff_date",LocalDate.class));
    }
    static SimulationException notFound() { return new SimulationException("SIM_NOT_FOUND","未找到本人的持仓诊断记录。"); }
}
