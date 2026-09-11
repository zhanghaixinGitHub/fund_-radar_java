package com.fundradar.core.advice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import static com.fundradar.core.advice.AdviceTypes.*;

/** 日报只追加；所有页面查询同时限定用户和基金，不以可猜测报告编号作为授权。 */
@Repository
public class AdviceRepository {
    private final JdbcClient db;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private static final String JOIN = """
        FROM portfolio_advice_report r LEFT JOIN portfolio_advice_review v
        ON v.report_id=COALESCE(r.original_report_id,r.report_id)
        """;
    private static final String SELECT = "SELECT r.*,v.status AS review_status,v.total_return,v.support ";
    public AdviceRepository(JdbcClient db) { this.db=db; }
    public String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch(Exception e) { throw new IllegalStateException("建议报告序列化失败",e); }
    }
    private <T> T decode(String value,Class<T> type) {
        try { return value==null ? null : json.readValue(value,type); }
        catch(Exception e) { throw new IllegalStateException("建议报告解析失败",e); }
    }
    public String hash(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode(value).getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    /** 去除读取时刻和观察窗口；网页刷新不制造新版本，跨日期留档由report_date区分。 */
    public String fingerprint(Snapshot value) {
        ObjectNode node=json.valueToTree(value);
        node.remove(List.of("observationStart","observationEnd"));
        if (node.get("experiment") instanceof ObjectNode experiment) experiment.remove("readAt");
        return hash(node);
    }
    public void lock(UUID user,String code) {
        db.sql("SELECT pg_advisory_xact_lock(721107,hashtext(:key))").param("key",user+":"+code).query((r,n)->0).list();
    }
    public boolean owns(UUID user,String code) {
        return db.sql("SELECT EXISTS(SELECT 1 FROM sim_position WHERE user_id=:user AND fund_code=:code) OR EXISTS(SELECT 1 FROM portfolio_advice_report WHERE user_id=:user AND fund_code=:code)")
                .param("user",user).param("code",code).query(Boolean.class).single();
    }
    public List<Summary> latest(UUID user) {
        return db.sql("SELECT DISTINCT ON(r.fund_code) r.*,v.status AS review_status,v.total_return,v.support " + JOIN
                + " WHERE r.user_id=:user ORDER BY r.fund_code,r.generated_at DESC,r.report_id DESC")
                .param("user",user).query((r,n)->summary(r)).list();
    }
    public Summary latest(UUID user,String code) {
        return db.sql(SELECT+JOIN+" WHERE r.user_id=:user AND r.fund_code=:code ORDER BY r.generated_at DESC,r.report_id DESC LIMIT 1")
                .param("user",user).param("code",code).query((r,n)->summary(r)).optional().orElse(null);
    }
    public Summary firstSample(UUID user,String code,String key) {
        if(key==null) return null;
        return db.sql(SELECT+JOIN+" WHERE r.user_id=:user AND r.fund_code=:code AND r.sample_key=:key AND r.original_report_id IS NULL ORDER BY r.generated_at,r.report_id LIMIT 1")
                .param("user",user).param("code",code).param("key",key).query((r,n)->summary(r)).optional().orElse(null);
    }
    public Detail detail(UUID user,String code,UUID id) {
        return db.sql(SELECT+",v.payload AS review_payload "+JOIN+" WHERE r.user_id=:user AND r.fund_code=:code AND r.report_id=:id")
                .param("user",user).param("code",code).param("id",id).query((r,n)-> {
                    Snapshot snapshot=decode(r.getString("snapshot"),Snapshot.class);
                    if(!hash(snapshot).equals(r.getString("content_hash"))) throw new IllegalStateException("原始建议快照完整性校验失败");
                    return new Detail(summary(r),snapshot,decode(r.getString("review_payload"),Outcome.class));
                }).optional().orElseThrow(AdviceRepository::notFound);
    }
    public UUID insert(UUID user,LocalDate today,Instant now,Snapshot snapshot,String key,String fingerprint,UUID original) {
        UUID id=UUID.randomUUID();
        db.sql("""
            INSERT INTO portfolio_advice_report(report_id,user_id,fund_code,fund_name,report_date,generated_at,
                decision,summary,rule_version,cutoff_date,observation_start,observation_end,original_report_id,
                sample_key,fingerprint,content_hash,snapshot)
            VALUES (:id,:user,:code,:name,:date,:now,:decision,:summary,:rule,:cutoff,:start,:end,:original,:key,:fingerprint,:hash,CAST(:snapshot AS jsonb))
            ON CONFLICT (user_id,fund_code,report_date,fingerprint) DO NOTHING
            """).param("id",id).param("user",user).param("code",snapshot.position().fundCode()).param("name",snapshot.position().fundName())
                .param("date",today).param("now",Timestamp.from(now)).param("decision",snapshot.decision()).param("summary",snapshot.summary())
                .param("rule",snapshot.ruleVersion()).param("cutoff",snapshot.experiment()==null ? null : snapshot.experiment().cutoffDate(),Types.DATE)
                .param("start",snapshot.observationStart(),Types.DATE).param("end",snapshot.observationEnd(),Types.DATE)
                .param("original",original,Types.OTHER).param("key",key,Types.VARCHAR).param("fingerprint",fingerprint)
                .param("hash",hash(snapshot)).param("snapshot",encode(snapshot)).update();
        return db.sql("SELECT report_id FROM portfolio_advice_report WHERE user_id=:user AND fund_code=:code AND report_date=:date AND fingerprint=:fingerprint")
                .param("user",user).param("code",snapshot.position().fundCode()).param("date",today).param("fingerprint",fingerprint).query(UUID.class).single();
    }
    private static final String FILTER = " WHERE r.user_id=:user AND r.fund_code=:code AND (:start IS NULL OR r.report_date>=:start) AND (:end IS NULL OR r.report_date<=:end) AND (:version IS NULL OR r.rule_version=:version) ";
    private JdbcClient.StatementSpec filtered(String sql,UUID user,String code,LocalDate start,LocalDate end,String version) {
        return db.sql(sql).param("user",user).param("code",code).param("start",start,Types.DATE).param("end",end,Types.DATE).param("version",version,Types.VARCHAR);
    }
    public Page<Summary> history(UUID user,String code,int page,int size,LocalDate start,LocalDate end,String version) {
        var rows=filtered(SELECT+JOIN+FILTER+" ORDER BY r.generated_at DESC,r.report_id DESC LIMIT :size OFFSET :offset",user,code,start,end,version)
                .param("size",size).param("offset",(long)(page-1)*size).query((r,n)->summary(r)).list();
        long count=filtered("SELECT count(*) FROM portfolio_advice_report r "+FILTER,user,code,start,end,version).query(Long.class).single();
        return new Page<>(rows,page,size,count);
    }
    /** 只统计首次有效报告；沿用与无建议各自计数，绝不把资料不足当作不支持。 */
    public Stats stats(UUID user,String code,LocalDate start,LocalDate end,String version) {
        return filtered("""
            SELECT count(*) AS reports,
            count(*) FILTER(WHERE r.decision<>'UNAVAILABLE' AND r.original_report_id IS NULL) AS samples,
            count(*) FILTER(WHERE r.original_report_id IS NULL AND v.status='ASSESSED') AS assessed,
            count(*) FILTER(WHERE r.original_report_id IS NULL AND v.support='SUPPORTED') AS supported,
            count(*) FILTER(WHERE r.original_report_id IS NULL AND v.support='UNSUPPORTED') AS unsupported,
            count(*) FILTER(WHERE r.original_report_id IS NULL AND v.support='FLAT') AS flat,
            count(*) FILTER(WHERE r.original_report_id IS NOT NULL) AS carried,
            count(*) FILTER(WHERE r.decision='UNAVAILABLE') AS no_advice
            """+JOIN+FILTER,user,code,start,end,version).query((r,n)->new Stats(r.getLong("reports"),r.getLong("samples"),
                r.getLong("assessed"),r.getLong("supported"),r.getLong("unsupported"),r.getLong("flat"),
                r.getLong("samples")-r.getLong("assessed"),r.getLong("carried"),r.getLong("no_advice"),version)).single();
    }
    public List<Pending> pending(LocalDate today,UUID after,int size) {
        return db.sql("""
            SELECT r.* FROM portfolio_advice_report r LEFT JOIN portfolio_advice_review v ON v.report_id=r.report_id
            WHERE r.original_report_id IS NULL AND r.decision<>'UNAVAILABLE' AND r.observation_end<:today
                AND (v.status IS NULL OR v.status<>'ASSESSED')
                AND (:after IS NULL OR r.report_id>CAST(:after AS uuid))
            ORDER BY r.report_id LIMIT :size
            """).param("today",today).param("after",after==null?null:after.toString(),Types.VARCHAR).param("size",size)
                .query((r,n)->new Pending(r.getObject("report_id",UUID.class),r.getString("fund_code"),r.getString("decision"),
                        r.getObject("observation_start",LocalDate.class),r.getObject("observation_end",LocalDate.class))).list();
    }
    public void review(Pending row,Outcome result) {
        if("WAITING".equals(result.status())) return;
        String support=null;
        if("ASSESSED".equals(result.status())) {
            int sign=result.totalReturn().signum();
            support=sign==0 ? "FLAT" : (sign>0)=="HOLD".equals(row.decision()) ? "SUPPORTED" : "UNSUPPORTED";
        }
        db.sql("""
            INSERT INTO portfolio_advice_review(report_id,status,total_return,support,checked_at,payload)
            VALUES(:id,:status,:value,:support,:now,CAST(:payload AS jsonb))
            ON CONFLICT(report_id) DO UPDATE SET status=EXCLUDED.status,total_return=EXCLUDED.total_return,
                support=EXCLUDED.support,checked_at=EXCLUDED.checked_at,payload=EXCLUDED.payload
            WHERE portfolio_advice_review.status<>'ASSESSED'
            """).param("id",row.reportId()).param("status",result.status()).param("value",result.totalReturn(),Types.NUMERIC)
                .param("support",support,Types.VARCHAR).param("now",Timestamp.from(result.checkedAt())).param("payload",encode(result)).update();
    }
    private Summary summary(ResultSet r) throws SQLException {
        String status=r.getString("review_status");
        if("UNAVAILABLE".equals(r.getString("decision"))) status="NOT_APPLICABLE";
        return new Summary(r.getObject("report_id",UUID.class),r.getString("fund_code"),r.getString("fund_name"),
                r.getObject("report_date",LocalDate.class),r.getTimestamp("generated_at").toInstant(),r.getString("decision"),
                r.getString("summary"),r.getString("rule_version"),r.getObject("cutoff_date",LocalDate.class),
                r.getObject("observation_start",LocalDate.class),r.getObject("observation_end",LocalDate.class),
                r.getObject("original_report_id",UUID.class),status==null ? "WAITING" : status,r.getBigDecimal("total_return"),r.getString("support"));
    }
    static SimulationException notFound() { return new SimulationException("SIM_NOT_FOUND","未找到本人的持仓建议记录。"); }
}
