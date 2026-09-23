package com.fundradar.core.advice;

import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static com.fundradar.core.advice.DecisionPolicyV2.*;

/** 隔离的单基金研究账本：同一决策组件，T+1份额、T+2赎回现金；费用假设明确版本化。 */
@Component
public class StrategyReplayEngine {
    private static final MathContext MC=MathContext.DECIMAL128;
    private static final BigDecimal ZERO=BigDecimal.ZERO;
    private final DecisionPolicyV2 policy;
    public StrategyReplayEngine(DecisionPolicyV2 policy) { this.policy=policy; }
    public record Frame(LocalDate date,BigDecimal nav,BigDecimal cashDividend,boolean redemptionPaused,Input input) {}
    public record FeeTier(int minDays,Integer maxDays,BigDecimal rate) {}
    public record Config(BigDecimal initialCash,BigDecimal buyFee,List<FeeTier> redemptionFees,int confirmationSessions,
                         int cashArrivalSessions,String assumption,String version) {}
    public record Trade(LocalDate date,String action,BigDecimal gross,BigDecimal fee,BigDecimal shares,
                        LocalDate cashAvailableOn,String reason) {}
    public record Point(LocalDate date,BigDecimal equity,BigDecimal availableCash,BigDecimal receivable,
                        BigDecimal shares,String action,String decisionHash) {}
    /** 每次卖出到下次买回/回放结束的机会对照；各区间可能重叠，不相加冒充组合收益。 */
    public record ExitReview(LocalDate soldOn,LocalDate boughtBackOn,LocalDate assessedUntil,double underlyingTotalReturn,
                             double avoidedDecline,double missedRise,BigDecimal exitFee,BigDecimal reentryFee) {}
    public record Result(String version,String strategyVersion,String mode,BigDecimal initialCash,BigDecimal finalEquity,
                         double netReturn,double maxDrawdown,double turnover,int tradeCount,BigDecimal fees,
                         int investedSessions,int cashOnlySessions,List<Trade> trades,List<Point> curve,
                         String assumption,List<String> executionNotes,List<ExitReview> exitReviews) {}
    private static final class Lot {
        BigDecimal shares; final LocalDate bought; final int available;
        Lot(BigDecimal shares,LocalDate bought,int available) {this.shares=shares;this.bought=bought;this.available=available;}
    }
    private record CashDue(BigDecimal amount,int available) {}
    /** 当时实际发出的动作和原文hash；版本切换只沿历史报告序列，不读取今天的路由。 */
    public record IssuedDecision(String action,String contentHash) {}

    public Result run(List<Frame> frames,Config config,String mode) {
        return run(frames,config,mode,Map.of());
    }
    public Result run(List<Frame> frames,Config config,String mode,Map<LocalDate,IssuedDecision> issued) {
        if(frames.isEmpty()||frames.size()>3000||config.initialCash().signum()<=0
            ||config.confirmationSessions()<1||config.cashArrivalSessions()<1) throw new IllegalArgumentException("回放范围或资金规则不正确");
        if(!Set.of("V2","BUY_HOLD","ISSUED_ADVICE").contains(mode)) throw new IllegalArgumentException("回放模式不正确");
        if(config.buyFee()==null||config.buyFee().signum()<0||config.buyFee().compareTo(BigDecimal.ONE)>=0||config.redemptionFees().isEmpty())
            throw new IllegalArgumentException("费用配置不正确");
        int nextDay=0;
        for(var tier:config.redemptionFees()) {
            if(tier.minDays()!=nextDay||tier.rate()==null||tier.rate().signum()<0||tier.rate().compareTo(BigDecimal.ONE)>=0
                    ||(tier.maxDays()!=null&&tier.maxDays()<tier.minDays())) throw new IllegalArgumentException("持有期费率必须无重叠且完整覆盖");
            nextDay=tier.maxDays()==null?-1:tier.maxDays()+1;
        }
        if(nextDay!=-1) throw new IllegalArgumentException("最长持有期费率未覆盖");
        BigDecimal cash=config.initialCash(),fees=ZERO,turnover=ZERO,peak=config.initialCash();
        var lots=new ArrayList<Lot>(); var due=new ArrayList<CashDue>();
        var trades=new ArrayList<Trade>(); var curve=new ArrayList<Point>(); var notes=new LinkedHashSet<String>();
        double drawdown=0; int invested=0,empty=0;
        LocalDate previous=null;
        for(int index=0;index<frames.size();index++) {
            var frame=frames.get(index);
            if(frame.nav()==null||frame.nav().signum()<=0||(previous!=null&&!frame.date().isAfter(previous)))
                throw new IllegalArgumentException("回放日期乱序或净值无效");
            previous=frame.date();
            for(var iterator=due.iterator();iterator.hasNext();) {
                var receipt=iterator.next(); if(receipt.available()<=index) {cash=cash.add(receipt.amount());iterator.remove();}
            }
            if(frame.cashDividend()!=null&&frame.cashDividend().signum()>0) {
                // 除息日按统一总回报假设再投，不在已扣管理费的净值外重复扣日常费。
                for(var lot:lots) lot.shares=lot.shares.multiply(BigDecimal.ONE.add(frame.cashDividend().divide(frame.nav(),MC)),MC);
            }
            BigDecimal shares=lots.stream().map(l->l.shares).reduce(ZERO,BigDecimal::add);
            BigDecimal available=ZERO;
            for(var lot:lots) if(lot.available<=index) available=available.add(lot.shares);
            boolean held=shares.signum()>0;
            Input original=frame.input();
            Input input=new Input(original.predictions(),original.trendRisk(),original.facts(),
                    original.missing(),held,original.preference(),original.defaultPreference(),original.holdingGainRate(),
                    original.currentDrawdown(),original.personalRule(),original.constraints());
            var decision=policy.decide(input);
            String action=decision.decision();
            if(mode.equals("BUY_HOLD")) action=index==0?"BUY":"HOLD";
            if(mode.equals("ISSUED_ADVICE")) {
                var saved=issued.get(frame.date());
                action=saved==null?"NO_REPORT":saved.action();
                if(saved==null) notes.add("缺少当时有效建议的日期不产生模拟成交；不补写为继续持有");
                if(saved!=null&&!Set.of("BUY","AVOID","ADD","HOLD","REDUCE","SELL").contains(action))
                    throw new IllegalArgumentException("历史建议动作不正确");
            }
            if("BUY".equals(action)||"ADD".equals(action)) {
                boolean pendingBuy=false;for(var lot:lots) if(lot.available>index) pendingBuy=true;
                BigDecimal ratio=mode.equals("BUY_HOLD")?BigDecimal.ONE:policy.positionRatio(action);
                BigDecimal budget=pendingBuy?ZERO:cash.multiply(ratio,MC);
                if(pendingBuy) notes.add("同向买入份额未确认，保留判断但不重复买入");
                if(budget.compareTo(new BigDecimal("0.01"))>=0) {
                    BigDecimal net=budget.divide(BigDecimal.ONE.add(config.buyFee()),MC);
                    BigDecimal fee=budget.subtract(net),units=net.divide(frame.nav(),MC);
                    cash=cash.subtract(budget);fees=fees.add(fee);turnover=turnover.add(budget);
                    lots.add(new Lot(units,frame.date(),index+config.confirmationSessions()));
                    trades.add(new Trade(frame.date(),action,budget,fee,units,null,"先决策再使用当日最终净值确认；份额T+1可卖"));
                } else notes.add("现金不足的买入/加仓保留判断，不重复使用在途资金");
            } else if("REDUCE".equals(action)||"SELL".equals(action)) {
                if(frame.redemptionPaused()) notes.add("暂停赎回期间保留减仓/卖出信号，不创建成交");
                else {
                    BigDecimal wanted=available.multiply(policy.positionRatio(action),MC);
                    BigDecimal sold=ZERO,gross=ZERO,fee=ZERO;
                    for(var lot:lots) {
                        if(lot.available>index||wanted.signum()<=0) continue;
                        BigDecimal units=lot.shares.min(wanted),amount=units.multiply(frame.nav(),MC);
                        long days=ChronoUnit.DAYS.between(lot.bought,frame.date());
                        BigDecimal rate=config.redemptionFees().stream().filter(t->days>=t.minDays()&&(t.maxDays()==null||days<=t.maxDays()))
                                .map(FeeTier::rate).findFirst().orElseThrow(()->new IllegalArgumentException("赎回费率未覆盖持有期"));
                        fee=fee.add(amount.multiply(rate,MC));gross=gross.add(amount);sold=sold.add(units);
                        lot.shares=lot.shares.subtract(units);wanted=wanted.subtract(units);
                    }
                    if(sold.signum()>0) {
                        int arrival=index+config.cashArrivalSessions();
                        due.add(new CashDue(gross.subtract(fee),arrival));fees=fees.add(fee);turnover=turnover.add(gross);
                        trades.add(new Trade(frame.date(),action,gross,fee,sold,arrival<frames.size()?frames.get(arrival).date():null,
                                "按买入批次和持有自然日计赎回费；现金到账前不可再次买入"));
                    }
                }
            }
            shares=lots.stream().map(l->l.shares).reduce(ZERO,BigDecimal::add);
            BigDecimal receivable=due.stream().map(CashDue::amount).reduce(ZERO,BigDecimal::add);
            BigDecimal equity=cash.add(receivable).add(shares.multiply(frame.nav(),MC));
            if(cash.signum()<0||receivable.signum()<0||shares.signum()<0) throw new IllegalStateException("回放会计守恒失败");
            peak=peak.max(equity); drawdown=Math.max(drawdown,1-equity.divide(peak,MC).doubleValue());
            if(shares.signum()>0) invested++; else empty++;
            curve.add(new Point(frame.date(),equity,cash,receivable,shares,action,
                    mode.equals("ISSUED_ADVICE")?(issued.containsKey(frame.date())?issued.get(frame.date()).contentHash():"NO_REPORT"):
                    com.fundradar.core.direction1d.Direction1dPolicy.hash(decision.toString())));
        }
        BigDecimal end=curve.get(curve.size()-1).equity();
        return new Result(config.version(),VERSION,mode,config.initialCash(),end,end.divide(config.initialCash(),MC).doubleValue()-1,
                drawdown,turnover.divide(config.initialCash(),MC).doubleValue(),trades.size(),fees,invested,empty,
                List.copyOf(trades),List.copyOf(curve),config.assumption(),List.copyOf(notes),exitReviews(frames,trades));
    }
    private List<ExitReview> exitReviews(List<Frame> frames,List<Trade> trades) {
        var result=new ArrayList<ExitReview>();
        for(int index=0;index<trades.size();index++) {
            var sale=trades.get(index);if(!Set.of("SELL","REDUCE").contains(sale.action())) continue;
            Trade reentry=null;
            for(int next=index+1;next<trades.size();next++) if(Set.of("BUY","ADD").contains(trades.get(next).action())) {reentry=trades.get(next);break;}
            LocalDate end=reentry==null?frames.get(frames.size()-1).date():reentry.date();
            BigDecimal previous=null,factor=BigDecimal.ONE;
            for(var frame:frames) {
                if(frame.date().isBefore(sale.date())||frame.date().isAfter(end)) continue;
                if(previous!=null) factor=factor.multiply(frame.nav().add(frame.cashDividend()==null?ZERO:frame.cashDividend()).divide(previous,MC),MC);
                previous=frame.nav();
            }
            double change=factor.doubleValue()-1;
            result.add(new ExitReview(sale.date(),reentry==null?null:reentry.date(),end,change,Math.max(0,-change),Math.max(0,change),
                    sale.fee(),reentry==null?ZERO:reentry.fee()));
        }
        return List.copyOf(result);
    }
    public static Config defaultConfig() {
        return new Config(new BigDecimal("10000"),new BigDecimal("0.001"),List.of(
                new FeeTier(0,6,new BigDecimal("0.015")),new FeeTier(7,29,new BigDecimal("0.005")),
                new FeeTier(30,null,new BigDecimal("0.001"))),1,2,
                "费用假设下回放：申购0.1%；持有不足7日赎回1.5%，7至29日0.5%，30日起0.1%；T+1可卖/T+2现金到账，不声称恢复真实渠道历史费率",
                "POSITION_POLICY_V1_FEE_ASSUMPTION_V1");
    }
}
