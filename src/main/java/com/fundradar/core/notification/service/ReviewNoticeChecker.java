package com.fundradar.core.notification.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fundradar.core.fund.api.FundNewsFactsResponse;
import com.fundradar.core.portfolio.AccountFundingRepository;
import com.fundradar.core.portfolio.AccountFundingTypes.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import static com.fundradar.core.notification.service.ReviewNoticeService.*;

/** 依据已确认条件与公共事实生成复查事项。未知历史回撤不触发损失提醒，模拟与本人确认持仓分开。 */
@Service
public class ReviewNoticeChecker {
    private final JdbcClient db;
    private final AccountFundingRepository funding;
    private final ReviewNoticeService notices;
    private final ObjectMapper json;
    public ReviewNoticeChecker(JdbcClient db,AccountFundingRepository funding,ReviewNoticeService notices,ObjectMapper json) {
        this.db=db;this.funding=funding;this.notices=notices;this.json=json;
    }
    /** 后台按用户主键分页，不将个人信息交给 Python；每位用户失败由调度单独记录。 */
    public List<UUID> usersAfter(UUID after) {
        return db.sql("""
            SELECT user_id FROM user_account u WHERE status='ACTIVE' AND user_id>:after AND (
                EXISTS(SELECT 1 FROM account_funding_preference p WHERE p.user_id=u.user_id)
                OR EXISTS(SELECT 1 FROM alert_rule r WHERE r.user_id=u.user_id AND r.rule_type='EVENT' AND r.fund_code='002112')
                OR EXISTS(SELECT 1 FROM review_notice n WHERE n.user_id=u.user_id)) ORDER BY user_id LIMIT 200
            """).param("after",after).query(UUID.class).list();
    }
    public int check(UUID user,FundNewsFactsResponse news,LocalDate today) {
        int changed=0;
        for(Scope scope:Scope.values()) {
            View value=funding.current(user,scope);
            for(String kind:List.of("USE_DATE","REVIEW_DUE")) {
                Observation observation=account(scope,kind,value,today);
                if(observation!=null) changed+=notices.observe(user,observation)?1:0;
                else changed+=closeAccount(user,scope,kind,value)?1:0;
            }
        }
        List<UUID> rules=db.sql("SELECT rule_id FROM alert_rule WHERE user_id=:user AND fund_code='002112' AND rule_type='EVENT' AND enabled=true")
                .param("user",user).query(UUID.class).list();
        if(news!=null && "002112".equals(news.fundCode()) && news.items()!=null && news.items().size()<=50) {
            for(UUID rule:rules) for(var item:news.items()) {
                Observation observation=publication(rule,item,today);
                if(observation!=null) changed+=notices.observe(user,observation)?1:0;
            }
        }
        // 来源读取失败不撤销事实。窗口到期由原保存日期决定；订阅停用只撤销本条提醒，不宣称事件消失。
        var stored=db.sql("SELECT business_key,scope,fund_code,kind,lifecycle,payload,valid_until FROM review_notice WHERE user_id=:user AND kind='FUND_PUBLICATION' AND lifecycle='ACTIVE' ORDER BY notice_id LIMIT 1001")
                .param("user",user).query((r,n)->new Observation(r.getString("business_key"),r.getString("scope"),r.getString("fund_code"),r.getString("kind"),
                        State.valueOf(r.getString("lifecycle")),decode(r.getString("payload")),r.getObject("valid_until",LocalDate.class))).list();
        if(stored.size()>1000) throw new IllegalStateException("单人待复查事项超出当前处理上限");
        for(Observation old:stored) {
            boolean enabled=rules.stream().anyMatch(rule->old.businessKey().startsWith("publication:"+rule+":"));
            State next=!enabled?State.RETRACTED:old.validUntil()!=null && today.isAfter(old.validUntil())?State.EXPIRED:null;
            if(next==null) continue;
            Map<String,Object> payload=new TreeMap<>(old.payload());
            payload.put("changeExplanation",!enabled?"你已停用相关基金事项提醒；原事实和历史仍保留。":"这条消息已结束30天信息观察窗口；不代表风险消失。");
            changed+=notices.observe(user,new Observation(old.businessKey(),null,old.fundCode(),old.kind(),next,payload,old.validUntil()))?1:0;
        }
        return changed;
    }
    /** 本人重确认开启新的复查间隔；未知、撤销和将来到期只更新已有提醒，不制造未发生事项。 */
    static Observation account(Scope scope,String kind,View value,LocalDate today) {
        if(value==null || !"ACTIVE".equals(value.status()) || value.input()==null) return null;
        LocalDate due=kind.equals("USE_DATE")?value.input().useDate():value.input().reviewIntervalDays()==null?null:
                value.confirmedAt().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().plusDays(value.input().reviewIntervalDays());
        if(due==null) return null;
        String label=scope==Scope.CONFIRMED?"本人确认持仓":"模拟组合";
        boolean active=!today.isBefore(due);
        Map<String,Object> payload=new TreeMap<>();
        payload.put("title",kind.equals("USE_DATE")?"你设定的用款日期已到达":"你设定的定期复查日期已到达");
        payload.put("condition",kind.equals("USE_DATE")?"预计用款日期："+due:"复查间隔："+value.input().reviewIntervalDays()+"天；复查日期："+due);
        payload.put("explanation",active?label+"的日期条件已达到，请复查资金安排和已录入持仓。此提醒不生成买卖动作。":"你已调整日期或重新确认安排，当前尚未达到该日期条件。");
        payload.put("basisDate",due.toString());payload.put("destination","/portfolio?section=funding");
        return new Observation("account:"+scope+":"+kind,scope.name(),null,kind,active?State.ACTIVE:State.RESOLVED,payload,null);
    }
    private boolean closeAccount(UUID user,Scope scope,String kind,View value) {
        var old=db.sql("SELECT payload FROM review_notice WHERE user_id=:user AND business_key=:key AND lifecycle<>'RETRACTED'")
                .param("user",user).param("key","account:"+scope+":"+kind).query(String.class).optional();
        if(old.isEmpty()) return false;
        Map<String,Object> payload=decode(old.get());
        payload.put("changeExplanation",value==null || "REVOKED".equals(value.status())?"本人安排已撤销，原条件与提醒历史保留。":"该项日期条件已留空，停止按原条件复查，不能解释为风险解除。");
        return notices.observe(user,new Observation("account:"+scope+":"+kind,scope.name(),null,kind,State.RETRACTED,payload,null));
    }
    /** 公告只是公开事实；稳定原文摘要用于去重，取得时间变动不制造第二次提醒。 */
    static Observation publication(UUID rule,FundNewsFactsResponse.Item item,LocalDate today) {
        if(item==null || item.eventId()==null || !item.eventId().matches("[a-f0-9]{64}") || item.evidenceHash()==null || !item.evidenceHash().matches("[a-f0-9]{64}") || !"已披露文件".equals(item.stage())) return null;
        try {
            LocalDate published=LocalDate.parse(item.publishedDate());
            LocalDate until=published.plusDays(30);
            var uri=java.net.URI.create(item.sourceUrl());
            // 已核验披露事实仅来自基金官网或巨潮原件；不能接受同名子域、外部转载或任意地址。
            boolean official="www.dbfund.com.cn".equals(uri.getHost())
                    || ("static.cninfo.com.cn".equals(uri.getHost())
                        && uri.getPath()!=null && uri.getPath().matches("/finalpage/[0-9]{4}-[0-9]{2}-[0-9]{2}/[0-9]+\\.[pP][dD][fF]"));
            if(published.isAfter(today) || today.isAfter(until) || !"https".equals(uri.getScheme())
                    || uri.getUserInfo()!=null || !official) return null;
            Map<String,Object> payload=new TreeMap<>();
            payload.put("title",item.title());payload.put("explanation",item.summary());payload.put("basisDate",item.publishedDate());
            payload.put("sourceName",item.sourceName());payload.put("sourceUrl",item.sourceUrl());payload.put("stage",item.stage());
            payload.put("relation",item.relation());payload.put("evidenceHash",item.evidenceHash());
            payload.put("destination","/funds/002112?section=documents");
            payload.put("limitation","文件披露不等于风险已形成或解除；本事项未进入当前涨跌预测。");
            return new Observation("publication:"+rule+":"+item.eventId(),null,"002112","FUND_PUBLICATION",State.ACTIVE,payload,until);
        } catch(RuntimeException ex) { return null; } // 无法核验的事实不发布，也不改写已有提醒。
    }
    private Map<String,Object> decode(String value) {
        try { return json.readValue(value,new TypeReference<TreeMap<String,Object>>(){}); }
        catch(Exception ex) { throw new IllegalStateException("原提醒依据无法读取",ex); }
    }
}
