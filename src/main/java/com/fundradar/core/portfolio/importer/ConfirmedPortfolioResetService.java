package com.fundradar.core.portfolio.importer;

import com.fundradar.core.auth.CurrentUserContext;
import com.fundradar.core.auth.PermissionCode;
import com.fundradar.core.simulation.SimulationRepository;
import com.fundradar.core.simulation.SimulationTypes.Plan;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * 用户明确要求后，从本机已核对截图重置本人组合；不提供网页写入口，不访问第三方金融账户。
 * 旧交易及收益在同一事务内删除，不备份；输入不完整或任一写入失败则整体回滚。
 */
@Service
public class ConfirmedPortfolioResetService {
    /** 截图中各自独立展示的累计期数、金额和下次扣款日；不能由金额反推期数或历史交易日期。 */
    public record ReportedPlan(String fundCode,BigDecimal amount,long orderedPeriods,BigDecimal investedAmount,LocalDate nextExecutionDate) {}
    public record Input(LocalPortfolioSnapshotInput snapshot,List<ReportedPlan> plans) {}
    public record Result(UUID snapshotId,int holdings,int plans,boolean alreadyApplied) {}
    private final SimulationRepository repo;
    private final JdbcClient db;
    private final TransactionTemplate tx;
    private final Clock clock;
    public ConfirmedPortfolioResetService(SimulationRepository repo,JdbcClient db,TransactionTemplate tx,Clock clock) {
        this.repo=repo; this.db=db; this.tx=tx; this.clock=clock;
    }

    /** 只接受当前已认证用户；先完整校验截图，再锁住本人账本，防止后台结算穿插进重置过程。 */
    public Result reset(Input input) {
        UUID user=CurrentUserContext.requirePermission(PermissionCode.SIM_PORTFOLIO_SELF_WRITE).userId();
        CurrentUserContext.requirePermission(PermissionCode.SIM_PLAN_SELF_WRITE);
        validate(input);
        return tx.execute(status -> {
            repo.lock(user);
            String hash=repo.hash(input);
            var previous=db.sql("""
                SELECT s.snapshot_id FROM sim_account a JOIN portfolio_snapshot s
                ON s.snapshot_id=a.baseline_snapshot_id AND s.user_id=a.user_id
                WHERE a.user_id=:u AND s.source_content_hash=:hash
                """).param("u",user).param("hash",hash).query(UUID.class).optional().orElse(null);
            // 重复执行同一次导入不得清除已经新增的交易。
            if(previous!=null) return new Result(previous,input.snapshot().holdings().size(),input.plans().size(),true);
            Set<String> followed=new HashSet<>(db.sql("SELECT fund_code FROM watchlist_item WHERE user_id=:u")
                    .param("u",user).query(String.class).list());
            Set<String> codes=new HashSet<>(); input.snapshot().holdings().forEach(h -> codes.add(h.fundCode()));
            if(!codes.equals(followed)) throw new IllegalArgumentException("截图基金与本人确认的关注名单不一致，未重置。");
            var now=clock.instant(); UUID snapshot=UUID.randomUUID();
            db.sql("""
                INSERT INTO portfolio_snapshot(snapshot_id,user_id,source_kind,data_as_of_date,data_as_of_status,
                    source_description,source_content_hash,imported_at)
                VALUES(:id,:u,:kind,:date,:asof,:description,:hash,:now)
                """).param("id",snapshot).param("u",user).param("kind",input.snapshot().sourceKind())
                    .param("date",input.snapshot().dataAsOfDate()).param("asof",input.snapshot().dataAsOfStatus())
                    .param("description",input.snapshot().sourceDescription()).param("hash",hash).param("now",Timestamp.from(now)).update();
            for(var h:input.snapshot().holdings()) {
                db.sql("""
                    INSERT INTO portfolio_holding_snapshot(holding_snapshot_id,snapshot_id,fund_code,fund_name,
                      reported_amount,reported_weight_pct,reported_daily_gain_amount,reported_holding_gain_amount,
                      reported_holding_gain_pct,reported_cumulative_gain_amount)
                    VALUES(:id,:snapshot,:code,:name,:amount,:weight,:daily,:holding,:rate,:cumulative)
                    """).param("id",UUID.randomUUID()).param("snapshot",snapshot).param("code",h.fundCode()).param("name",h.fundName())
                        .param("amount",h.reportedAmount()).param("weight",h.reportedWeightPct()).param("daily",h.reportedDailyGainAmount())
                        .param("holding",h.reportedHoldingGainAmount()).param("rate",h.reportedHoldingGainPct())
                        .param("cumulative",h.reportedCumulativeGainAmount()).update();
            }
            // 先删除有外键依赖的执行记录，再删除本人旧账本；所有 SQL 都包含本人归属条件。
            db.sql("DELETE FROM sim_plan_execution WHERE plan_id IN (SELECT plan_id FROM sim_plan WHERE user_id=:u)")
                    .param("u",user).update();
            for(String table:List.of("sim_order","sim_daily_valuation","sim_ledger_entry","sim_position","sim_plan",
                    "holding_rule_profile","holding_rule_draft","portfolio_advice_report","holding_diagnosis_report","portfolio_decision_report")) {
                db.sql("DELETE FROM "+table+" WHERE user_id=:u").param("u",user).update();
            }
            db.sql("UPDATE sim_account SET baseline_snapshot_id=:id WHERE user_id=:u").param("id",snapshot).param("u",user).update();
            db.sql("DELETE FROM portfolio_snapshot WHERE user_id=:u AND snapshot_id<>:id").param("u",user).param("id",snapshot).update();
            Map<String,String> names=new HashMap<>(); input.snapshot().holdings().forEach(h -> names.put(h.fundCode(),h.fundName()));
            for(var p:input.plans()) {
                UUID id=UUID.randomUUID();
                repo.savePlan(new Plan(id,user,p.fundCode(),names.get(p.fundCode()),p.amount(),"DAILY",1,p.nextExecutionDate(),
                        null,null,p.nextExecutionDate(),p.nextExecutionDate(),"ACTIVE",1,UUID.randomUUID(),now,0,BigDecimal.ZERO));
                db.sql("UPDATE sim_plan SET reported_ordered_periods=:periods,reported_invested_amount=:amount WHERE user_id=:u AND plan_id=:id")
                        .param("periods",p.orderedPeriods()).param("amount",p.investedAmount()).param("u",user).param("id",id).update();
            }
            // 审计仅记录本次重置动作及新快照标识，不复制旧金额或旧交易内容。
            repo.audit(user,"PORTFOLIO_SCREENSHOT_RESET",snapshot.toString());
            return new Result(snapshot,input.snapshot().holdings().size(),input.plans().size(),false);
        });
    }

    private void validate(Input input) {
        if(input==null || input.snapshot()==null || input.plans()==null) throw new IllegalArgumentException("截图输入不完整。");
        var s=input.snapshot();
        if(!"USER_CONFIRMED_SCREENSHOT".equals(s.sourceKind()) || s.sourceDescription()==null || s.sourceDescription().isBlank()
                || s.sourceDescription().length()>512 || s.holdings()==null || s.holdings().isEmpty() || s.holdings().size()>500
                || input.plans().size()>500) throw new IllegalArgumentException("截图来源或基金数量无效。");
        if(!("UNKNOWN".equals(s.dataAsOfStatus()) && s.dataAsOfDate()==null
                || "KNOWN".equals(s.dataAsOfStatus()) && s.dataAsOfDate()!=null)) throw new IllegalArgumentException("截图日期状态不一致。");
        Set<String> codes=new HashSet<>();
        for(var h:s.holdings()) {
            if(h==null || h.fundCode()==null || !h.fundCode().matches("[0-9]{6}") || !codes.add(h.fundCode())
                    || h.fundName()==null || h.fundName().isBlank() || h.fundName().length()>256
                    || !decimal(h.reportedAmount(),18,2) || h.reportedAmount().signum()<0
                    || !decimal(h.reportedWeightPct(),5,4) || h.reportedWeightPct().signum()<0 || h.reportedWeightPct().compareTo(new BigDecimal("100"))>0
                    || !decimal(h.reportedDailyGainAmount(),18,2) || !decimal(h.reportedHoldingGainAmount(),18,2)
                    || !decimal(h.reportedHoldingGainPct(),5,4) || !decimal(h.reportedCumulativeGainAmount(),18,2))
                throw new IllegalArgumentException("截图基金重复或金额字段不完整。");
        }
        Set<String> plans=new HashSet<>(); LocalDate today=LocalDate.ofInstant(clock.instant(),ZoneId.of("Asia/Shanghai"));
        for(var p:input.plans()) {
            if(p==null || !codes.contains(p.fundCode()) || !plans.add(p.fundCode()) || !decimal(p.amount(),9,2)
                    || p.amount().signum()<=0 || p.orderedPeriods()<0 || !decimal(p.investedAmount(),18,2)
                    || p.investedAmount().signum()<0 || p.nextExecutionDate()==null || !p.nextExecutionDate().isAfter(today))
                throw new IllegalArgumentException("定投截图字段无效或下次执行日已过去。");
        }
    }
    private boolean decimal(BigDecimal value,int integers,int scale) {
        return value!=null && value.scale()<=scale && value.precision()-value.scale()<=integers;
    }
}
