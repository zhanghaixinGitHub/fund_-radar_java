package com.fundradar.core.advice;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fundradar.core.simulation.SimulationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import static com.fundradar.core.advice.RuleTypes.*;

/** 草案只追加；规则允许状态流转（ACTIVE→REVOKED），阈值与档位字段不随状态更新改动。 */
@Repository
public class RuleRepository {
    private final JdbcClient db;
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true);
    public RuleRepository(JdbcClient db) { this.db=db; }
    public String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch(Exception e) { throw new IllegalStateException("规则草案序列化失败",e); }
    }
    private <T> T decode(String value,TypeReference<T> type) {
        try { return value==null ? null : json.readValue(value,type); }
        catch(Exception e) { throw new IllegalStateException("规则草案解析失败",e); }
    }
    public String hash(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode(value).getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    /** 归档的stats列包含样本量、口径、假设与原始统计，草案可复算可审计。 */
    public Map<String,Object> storedStats(DraftStats value) {
        var map=new LinkedHashMap<String,Object>();
        map.put("historyDays",value.historyDays()); map.put("windowCount",value.windowCount());
        map.put("navBasis",value.navBasis()); map.put("assumption",value.assumption()); map.put("stats",value.stats());
        return map;
    }
    /** 草案指纹只含统计内容；净值水位未推进时指纹不变，不重复生成。 */
    public String fingerprint(DraftStats value) {
        return hash(List.of(String.valueOf(value.statsCutoffDate()),storedStats(value),value.tiers()));
    }
    public void lock(UUID user,String code) {
        db.sql("SELECT pg_advisory_xact_lock(721109,hashtext(:key))").param("key",user+":"+code).query((r,n)->0).list();
    }
    public boolean owns(UUID user,String code) {
        return db.sql("""
            SELECT EXISTS(SELECT 1 FROM sim_position WHERE user_id=:user AND fund_code=:code)
                OR EXISTS(SELECT 1 FROM holding_rule_draft WHERE user_id=:user AND fund_code=:code)
                OR EXISTS(SELECT 1 FROM holding_rule_profile WHERE user_id=:user AND fund_code=:code)
            """).param("user",user).param("code",code).query(Boolean.class).single();
    }
    public DraftRow latestDraft(UUID user,String code) {
        return db.sql("SELECT * FROM holding_rule_draft WHERE user_id=:user AND fund_code=:code ORDER BY generated_at DESC,draft_id DESC LIMIT 1")
                .param("user",user).param("code",code).query((r,n)->draft(r)).optional().orElse(null);
    }
    public DraftRow draft(UUID user,UUID id) {
        return db.sql("SELECT * FROM holding_rule_draft WHERE user_id=:user AND draft_id=:id")
                .param("user",user).param("id",id).query((r,n)->draft(r)).optional().orElse(null);
    }
    /** 统计未变时沿用既有草案；指纹变化追加新草案，不覆盖历史。 */
    public DraftRow insertDraft(UUID user,String code,Instant now,DraftStats stats,String fingerprint) {
        db.sql("""
            INSERT INTO holding_rule_draft(draft_id,user_id,fund_code,generated_at,stats_cutoff_date,stats,tiers,fingerprint)
            VALUES (:id,:user,:code,:now,:cutoff,CAST(:stats AS jsonb),CAST(:tiers AS jsonb),:fingerprint)
            ON CONFLICT (user_id,fund_code,fingerprint) DO NOTHING
            """).param("id",UUID.randomUUID()).param("user",user).param("code",code).param("now",Timestamp.from(now))
                .param("cutoff",stats.statsCutoffDate()).param("stats",encode(storedStats(stats))).param("tiers",encode(stats.tiers()))
                .param("fingerprint",fingerprint).update();
        return db.sql("SELECT * FROM holding_rule_draft WHERE user_id=:user AND fund_code=:code AND fingerprint=:fingerprint")
                .param("user",user).param("code",code).param("fingerprint",fingerprint).query((r,n)->draft(r)).single();
    }
    public RuleView activeRule(UUID user,String code) {
        return db.sql("SELECT * FROM holding_rule_profile WHERE user_id=:user AND fund_code=:code AND status='ACTIVE'")
                .param("user",user).param("code",code).query((r,n)->rule(r)).optional().orElse(null);
    }
    public List<RuleView> ruleHistory(UUID user,String code) {
        return db.sql("SELECT * FROM holding_rule_profile WHERE user_id=:user AND fund_code=:code ORDER BY confirmed_at DESC,rule_id DESC")
                .param("user",user).param("code",code).query((r,n)->rule(r)).list();
    }
    public RuleView insertRule(UUID user,String code,String tier,java.math.BigDecimal takeProfit,java.math.BigDecimal reduceDrawdown,
                               Map<String,Object> params,UUID sourceDraft,Instant now) {
        UUID id=UUID.randomUUID();
        db.sql("""
            INSERT INTO holding_rule_profile(rule_id,user_id,fund_code,tier,take_profit_pct,reduce_drawdown_pct,
                rule_params,source_draft_id,rule_version,status,confirmed_at)
            VALUES (:id,:user,:code,:tier,:profit,:reduce,CAST(:params AS jsonb),:draft,:version,'ACTIVE',:now)
            """).param("id",id).param("user",user).param("code",code).param("tier",tier)
                .param("profit",takeProfit).param("reduce",reduceDrawdown).param("params",encode(params))
                .param("draft",sourceDraft,java.sql.Types.OTHER).param("version",VERSION).param("now",Timestamp.from(now)).update();
        return db.sql("SELECT * FROM holding_rule_profile WHERE rule_id=:id").param("id",id).query((r,n)->rule(r)).single();
    }
    /** 新确认取代旧规则：置REVOKED并记录取代时刻；主动撤销只置REVOKED。 */
    public void supersede(UUID ruleId,Instant now) {
        db.sql("UPDATE holding_rule_profile SET status='REVOKED',superseded_at=:now WHERE rule_id=:id AND status='ACTIVE'")
                .param("now",Timestamp.from(now)).param("id",ruleId).update();
    }
    public boolean revoke(UUID ruleId) {
        return db.sql("UPDATE holding_rule_profile SET status='REVOKED' WHERE rule_id=:id AND status='ACTIVE'")
                .param("id",ruleId).update()>0;
    }
    private DraftRow draft(ResultSet r) throws SQLException {
        return new DraftRow(r.getObject("draft_id",UUID.class),r.getTimestamp("generated_at").toInstant(),
                r.getObject("stats_cutoff_date",LocalDate.class),decode(r.getString("stats"),new TypeReference<>() {}),
                decode(r.getString("tiers"),new TypeReference<>() {}),r.getString("fingerprint"));
    }
    private RuleView rule(ResultSet r) throws SQLException {
        return new RuleView(r.getObject("rule_id",UUID.class),r.getString("fund_code"),r.getString("tier"),
                r.getBigDecimal("take_profit_pct"),r.getBigDecimal("reduce_drawdown_pct"),
                decode(r.getString("rule_params"),new TypeReference<>() {}),r.getObject("source_draft_id",UUID.class),
                r.getString("rule_version"),r.getString("status"),r.getTimestamp("confirmed_at").toInstant(),
                Optional.ofNullable(r.getTimestamp("superseded_at")).map(Timestamp::toInstant).orElse(null));
    }
    static SimulationException notFound() { return new SimulationException("SIM_NOT_FOUND","未找到本人的持仓规则记录。"); }
}
