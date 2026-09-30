package com.fundradar.core.notification.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fundradar.core.auth.CurrentUserContext;
import com.fundradar.core.auth.PermissionCode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;

/** 站内状态机。调用方只能提供已核对事实；失败和来源暂缺不得解释为撤销或风险解除。 */
@Service
public class ReviewNoticeService {
    public enum State { ACTIVE, RESOLVED, RETRACTED, EXPIRED }
    public record Observation(String businessKey,String scope,String fundCode,String kind,State lifecycle,
                              Map<String,Object> payload,LocalDate validUntil) {}
    public record Notice(UUID noticeId,String scope,String fundCode,String kind,long revision,State lifecycle,
                         JsonNode payload,Instant createdAt,Instant updatedAt,boolean read) {}
    public record Revision(long revision,State lifecycle,JsonNode payload,Instant recordedAt) {}
    public record Page<T>(List<T> items,int page,boolean hasMore) {}
    private final JdbcClient db;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(ReviewNoticeService.class);
    public ReviewNoticeService(JdbcClient db,ObjectMapper json,TransactionTemplate tx) { this.db=db;this.json=json;this.tx=tx; }

    /** 不对外开放 user 参数；Controller 仅可使用下面的本人方法。内部检查按稳定键加事务锁。 */
    public boolean observe(UUID user,Observation input) {
        if(input==null || input.lifecycle()==null || input.businessKey()==null || input.businessKey().length()>256)
            throw new IllegalArgumentException("复查依据不完整。");
        String payload=encode(new TreeMap<>(input.payload()));
        String hash=hash(encode(Arrays.asList(input.scope(),input.fundCode(),input.kind(),input.lifecycle(),input.validUntil(),payload)));
        return Boolean.TRUE.equals(tx.execute(status->{
            db.sql("SELECT pg_advisory_xact_lock(721132,hashtext(:key))").param("key",user+":"+input.businessKey()).query((r,n)->0).list();
            var current=db.sql("SELECT notice_id,revision,material_hash FROM review_notice WHERE user_id=:user AND business_key=:key")
                    .param("user",user).param("key",input.businessKey()).query((r,n)->new Existing(r.getObject(1,UUID.class),r.getLong(2),r.getString(3))).optional().orElse(null);
            if(current!=null && current.hash().equals(hash)) return false;
            if(current==null && input.lifecycle()!=State.ACTIVE) return false; // 未发生事项不创建“已解除”提醒。
            UUID id=current==null?UUID.randomUUID():current.id();
            long revision=current==null?1:current.revision()+1;
            if(current==null) db.sql("""
                INSERT INTO review_notice(notice_id,user_id,business_key,scope,fund_code,kind,revision,lifecycle,material_hash,payload,valid_until)
                VALUES(:id,:user,:key,:scope,:fund,:kind,1,:state,:hash,CAST(:payload AS jsonb),:until)
                """).param("id",id).param("user",user).param("key",input.businessKey()).param("scope",input.scope())
                    .param("fund",input.fundCode()).param("kind",input.kind()).param("state",input.lifecycle().name())
                    .param("hash",hash).param("payload",payload).param("until",input.validUntil()).update();
            else db.sql("""
                UPDATE review_notice SET revision=:revision,lifecycle=:state,material_hash=:hash,payload=CAST(:payload AS jsonb),
                    valid_until=:until,updated_at=CURRENT_TIMESTAMP WHERE notice_id=:id AND user_id=:user
                """).param("revision",revision).param("state",input.lifecycle().name()).param("hash",hash).param("payload",payload)
                    .param("until",input.validUntil()).param("id",id).param("user",user).update();
            db.sql("INSERT INTO review_notice_revision(notice_id,revision,lifecycle,material_hash,payload) VALUES(:id,:revision,:state,:hash,CAST(:payload AS jsonb))")
                    .param("id",id).param("revision",revision).param("state",input.lifecycle().name()).param("hash",hash).param("payload",payload).update();
            LOG.info("ReviewNoticeService.observe   >>> 站内事项变化, noticeId={}, kind={}, state={}, revision={}",id,input.kind(),input.lifecycle(),revision);
            return true;
        }));
    }
    private record Existing(UUID id,long revision,String hash) {}
    public Page<Notice> read(int page) {
        UUID user=CurrentUserContext.requirePermission(PermissionCode.NOTIFICATION_SELF_READ).userId();checkPage(page);
        var rows=db.sql("SELECT * FROM review_notice WHERE user_id=:user ORDER BY updated_at DESC,notice_id DESC LIMIT 21 OFFSET :offset")
                .param("user",user).param("offset",(page-1)*20).query((r,n)->notice(r)).list();
        return new Page<>(rows.stream().limit(20).toList(),page,rows.size()>20);
    }
    public Page<Revision> history(UUID id,int page) {
        UUID user=CurrentUserContext.requirePermission(PermissionCode.NOTIFICATION_SELF_READ).userId();checkPage(page);owned(user,id);
        var rows=db.sql("""
            SELECT h.* FROM review_notice_revision h JOIN review_notice n USING(notice_id)
            WHERE n.notice_id=:id AND n.user_id=:user ORDER BY h.revision DESC LIMIT 21 OFFSET :offset
            """).param("id",id).param("user",user).param("offset",(page-1)*20).query((r,n)->new Revision(r.getLong("revision"),
                    State.valueOf(r.getString("lifecycle")),decode(r.getString("payload")),r.getTimestamp("recorded_at").toInstant())).list();
        return new Page<>(rows.stream().limit(20).toList(),page,rows.size()>20);
    }
    /** 仅确认页面实际看到的版本，旧页面不能误把刚到达的新证据标为已读。 */
    public Notice markRead(UUID id,long revision) {
        UUID user=CurrentUserContext.requirePermission(PermissionCode.NOTIFICATION_SELF_WRITE).userId();
        if(revision<1) throw new IllegalArgumentException("请刷新后查看提醒。");
        return tx.execute(status->{
            Notice current=owned(user,id);
            if(revision>current.revision()) throw new IllegalArgumentException("请刷新后查看提醒。");
            db.sql("UPDATE review_notice SET read_revision=GREATEST(read_revision,:revision),read_at=CURRENT_TIMESTAMP WHERE notice_id=:id AND user_id=:user AND read_revision<:revision")
                    .param("revision",revision).param("id",id).param("user",user).update();
            return owned(user,id);
        });
    }
    private Notice owned(UUID user,UUID id) {
        return db.sql("SELECT * FROM review_notice WHERE notice_id=:id AND user_id=:user").param("id",id).param("user",user)
                .query((r,n)->notice(r)).optional().orElseThrow(NotificationNotFoundException::new);
    }
    private Notice notice(ResultSet r) throws SQLException {
        return new Notice(r.getObject("notice_id",UUID.class),r.getString("scope"),r.getString("fund_code"),r.getString("kind"),r.getLong("revision"),
                State.valueOf(r.getString("lifecycle")),decode(r.getString("payload")),r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant(),r.getLong("read_revision")==r.getLong("revision"));
    }
    private void checkPage(int page) { if(page<1 || page>10000) throw new IllegalArgumentException("页码超出范围。"); }
    private JsonNode decode(String value) { try { return json.readTree(value); } catch(Exception e) { throw new IllegalStateException("提醒内容暂时无法读取",e); } }
    private String encode(Object value) { try { return json.writeValueAsString(value); } catch(Exception e) { throw new IllegalStateException("提醒依据暂时无法核对",e); } }
    private String hash(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch(Exception e) { throw new IllegalStateException(e); } }
}
