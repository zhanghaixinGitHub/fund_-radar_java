package com.fundradar.core.advice;

import com.fundradar.core.auth.CurrentUserContext;
import com.fundradar.core.auth.PermissionCode;
import com.fundradar.core.simulation.SimulationException;
import com.fundradar.core.simulation.SimulationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import static com.fundradar.core.advice.RuleTypes.*;

/** 规则只能由本人显式确认；系统不自动补写，未确认草案只是参考数字，不进入任何建议。 */
@Service
public class RuleService {
    private static final BigDecimal TOLERANCE=new BigDecimal("0.2");
    private final RuleRepository repo;
    private final SimulationRepository positions;
    private final DraftStatsClient drafts;
    private final TransactionTemplate tx;
    private final Clock clock;
    public RuleService(RuleRepository repo,SimulationRepository positions,DraftStatsClient drafts,
                       TransactionTemplate tx,Clock clock) {
        this.repo=repo; this.positions=positions; this.drafts=drafts; this.tx=tx; this.clock=clock;
    }
    private UUID reader() { return CurrentUserContext.requirePermission(PermissionCode.PORTFOLIO_SELF_READ).userId(); }
    private UUID writer() {
        UUID user=CurrentUserContext.requirePermission(PermissionCode.SIM_PORTFOLIO_SELF_WRITE).userId();
        CurrentUserContext.requirePermission(PermissionCode.PORTFOLIO_SELF_READ);
        return user;
    }
    private void owns(UUID user,String code) {
        if(code==null || !code.matches("[0-9]{6}")) throw new SimulationException("SIM_INVALID","基金代码格式不正确。");
        if(!repo.owns(user,code)) throw RuleRepository.notFound();
    }
    /** 无草案时现场生成一次（与调度共用同一幂等留档逻辑）；数据不足如实返回状态与原因，不给数字。 */
    public DraftView draft(String code) {
        UUID user=reader(); owns(user,code);
        var existing=repo.latestDraft(user,code);
        if(existing!=null) return view(existing);
        return generate(user,code,clock.instant());
    }
    public DraftView generate(String code) {
        UUID user=writer(); owns(user,code);
        return generate(user,code,clock.instant());
    }
    private DraftView generate(UUID user,String code,Instant now) {
        DraftStats stats;
        try { stats=drafts.read(code); }
        catch(RuntimeException error) {
            return new DraftView(null,code,"DATA_INSUFFICIENT","草案统计来源暂时不可用，请稍后重试。",null,
                    null,null,null,null,null,List.of(),null);
        }
        if(!"AVAILABLE".equals(stats.status()))
            return new DraftView(null,code,stats.status(),stats.reason(),stats.statsCutoffDate(),null,
                    stats.historyDays(),stats.windowCount(),stats.navBasis(),null,List.of(),stats.assumption());
        var row=tx.execute(status-> {
            repo.lock(user,code);
            return repo.insertDraft(user,code,now,stats,repo.fingerprint(stats));
        });
        return view(row);
    }
    /** 调度批次共用：仅AVAILABLE结果留档，统计指纹未变时唯一键幂等；失败或不适用保留旧草案与已确认规则。 */
    public void refreshDraft(UUID user,String code,DraftStats stats,Instant now) {
        if(!"AVAILABLE".equals(stats.status())) return;
        tx.execute(status-> {
            repo.lock(user,code);
            repo.insertDraft(user,code,now,stats,repo.fingerprint(stats));
            return null;
        });
    }
    private static DraftView view(DraftRow row) {
        Map<String,Object> stored=row.stats()==null ? Map.of() : row.stats();
        return new DraftView(row.draftId(),null,"AVAILABLE",null,row.statsCutoffDate(),row.generatedAt(),
                stored.get("historyDays") instanceof Number n ? n.intValue() : null,
                stored.get("windowCount") instanceof Number n ? n.intValue() : null,
                Objects.toString(stored.get("navBasis"),null),
                stored.get("stats") instanceof Map<?,?> m ? castMap(m) : null,
                row.tiers(),Objects.toString(stored.get("assumption"),null));
    }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> castMap(Map<?,?> value) { return (Map<String,Object>)value; }
    public RulesView rules(String code) {
        UUID user=reader(); owns(user,code);
        return new RulesView(code,repo.activeRule(user,code),repo.ruleHistory(user,code));
    }
    /** 显式确认所选档位或微调值；同一参数重复确认不生成新版本；新确认取代旧规则并留痕。 */
    public RulesView confirm(String code,ConfirmRequest request) {
        UUID user=writer(); owns(user,code);
        var draft=repo.latestDraft(user,code);
        if(draft==null) throw new SimulationException("SIM_INVALID","尚无可用草案，无法确认规则。");
        var decided=decide(draft,request);
        var active=repo.activeRule(user,code);
        if(active!=null && active.tier().equals(decided.tier())
                && active.takeProfitPct().compareTo(decided.takeProfit())==0
                && active.reduceDrawdownPct().compareTo(decided.reduceDrawdown())==0)
            return new RulesView(code,active,repo.ruleHistory(user,code));
        Instant now=clock.instant();
        var params=new LinkedHashMap<String,Object>();
        params.put("sourceTier",request.tier());
        params.put("draftTakeProfitPct",decided.draftProfit().toPlainString());
        params.put("draftReduceDrawdownPct",decided.draftReduce().toPlainString());
        params.put("adjusted",decided.custom());
        RuleView confirmed=tx.execute(status-> {
            repo.lock(user,code);
            var current=repo.activeRule(user,code);
            if(current!=null) repo.supersede(current.ruleId(),now);
            var created=repo.insertRule(user,code,decided.tier(),decided.takeProfit(),decided.reduceDrawdown(),
                    params,draft.draftId(),now);
            positions.audit(user,"holding-rule-confirm",code+":"+created.ruleId());
            return created;
        });
        return new RulesView(code,confirmed,repo.ruleHistory(user,code));
    }
    public RulesView revoke(String code) {
        UUID user=writer(); owns(user,code);
        var active=repo.activeRule(user,code);
        if(active!=null) tx.execute(status-> {
            repo.lock(user,code);
            if(repo.revoke(active.ruleId())) positions.audit(user,"holding-rule-revoke",code+":"+active.ruleId());
            return null;
        });
        return new RulesView(code,null,repo.ruleHistory(user,code));
    }
    private record Decision(String tier,BigDecimal takeProfit,BigDecimal reduceDrawdown,
                            BigDecimal draftProfit,BigDecimal draftReduce,boolean custom) {}
    /** 微调限草案分位值±20%（恰好±20%允许），超出拒绝；有微调即记CUSTOM。 */
    private static Decision decide(DraftRow draft,ConfirmRequest request) {
        if(request==null || request.tier()==null || !TIERS.contains(request.tier()))
            throw new IllegalArgumentException("档位必须是 CONSERVATIVE、BALANCED 或 LOOSE。");
        var tier=draft.tiers().stream().filter(t->t.tier().equals(request.tier())).findFirst()
                .orElseThrow(()->new IllegalArgumentException("草案中没有所选档位。"));
        BigDecimal draftReduce=tier.reduceDrawdownPct(),draftProfit=tier.takeProfitPct();
        BigDecimal reduce=request.reduceDrawdownPct()==null ? draftReduce : request.reduceDrawdownPct();
        BigDecimal profit=request.takeProfitPct()==null ? draftProfit : request.takeProfitPct();
        if(profit.signum()<0 || reduce.signum()>0)
            throw new IllegalArgumentException("止盈线不能为负，减仓线（回撤）不能为正。");
        boolean custom=reduce.compareTo(draftReduce)!=0 || profit.compareTo(draftProfit)!=0;
        if(custom && (!within(reduce,draftReduce) || !within(profit,draftProfit)))
            throw new IllegalArgumentException("微调幅度限草案分位值 ±20% 以内。");
        return new Decision(custom ? "CUSTOM" : request.tier(),
                profit.setScale(4,RoundingMode.HALF_UP),reduce.setScale(4,RoundingMode.HALF_UP),
                draftProfit,draftReduce,custom);
    }
    private static boolean within(BigDecimal value,BigDecimal base) {
        return value.subtract(base).abs().compareTo(base.abs().multiply(TOLERANCE))<=0;
    }
}
