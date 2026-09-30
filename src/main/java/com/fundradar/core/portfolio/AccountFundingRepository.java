package com.fundradar.core.portfolio;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import static com.fundradar.core.portfolio.AccountFundingTypes.*;

/** 查询和插入均带本人归属；历史不可变，由当前范围的最高版本决定是否有效。 */
@Repository
public class AccountFundingRepository {
    private final JdbcClient db;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    public AccountFundingRepository(JdbcClient db) { this.db=db; }
    String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch(Exception ex) { throw new IllegalStateException("资金安排暂时无法保存",ex); }
    }
    public String hash(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode(value).getBytes(StandardCharsets.UTF_8))); }
        catch(Exception ex) { throw new IllegalStateException("资金安排暂时无法核对",ex); }
    }
    public void lock(UUID user) {
        db.sql("SELECT pg_advisory_xact_lock(721131,hashtext(:user))").param("user",user.toString()).query((r,n)->0).list();
    }
    public View current(UUID user,Scope scope) {
        return db.sql("SELECT * FROM account_funding_preference WHERE user_id=:user AND scope=:scope ORDER BY revision DESC LIMIT 1")
                .param("user",user).param("scope",scope.name()).query((r,n)->view(r)).optional().orElse(null);
    }
    public List<View> history(UUID user,Scope scope,int page) {
        return db.sql("SELECT * FROM account_funding_preference WHERE user_id=:user AND scope=:scope ORDER BY revision DESC LIMIT 21 OFFSET :offset")
                .param("user",user).param("scope",scope.name()).param("offset",(page-1)*20).query((r,n)->view(r)).list();
    }
    public record SavedRequest(String hash,View view) {}
    public SavedRequest request(UUID user,UUID request) {
        return db.sql("SELECT * FROM account_funding_preference WHERE user_id=:user AND request_id=:request")
                .param("user",user).param("request",request).query((r,n)->new SavedRequest(r.getString("request_hash"),view(r))).optional().orElse(null);
    }
    public View insert(UUID user,Scope scope,long revision,String status,UUID request,String hash,Input input) {
        return db.sql("""
            INSERT INTO account_funding_preference(preference_id,user_id,scope,revision,status,request_id,request_hash,payload)
            VALUES(:id,:user,:scope,:revision,:status,:request,:hash,CAST(:payload AS jsonb)) RETURNING *
            """).param("id",UUID.randomUUID()).param("user",user).param("scope",scope.name()).param("revision",revision)
                .param("status",status).param("request",request).param("hash",hash).param("payload",encode(input))
                .query((r,n)->view(r)).single();
    }
    private View view(ResultSet row) throws SQLException {
        try {
            return new View(row.getObject("preference_id",UUID.class),Scope.valueOf(row.getString("scope")),row.getLong("revision"),
                    row.getString("status"),json.readValue(row.getString("payload"),Input.class),row.getTimestamp("confirmed_at").toInstant());
        } catch(com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException("资金安排暂时无法读取",ex); }
    }
}
