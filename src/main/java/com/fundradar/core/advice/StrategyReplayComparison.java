package com.fundradar.core.advice;

import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.*;
import static com.fundradar.core.advice.StrategyReplayEngine.*;

/** 回放比较辅助。事前固定五档初始持仓对照，不根据收益挑最好档位，不把重叠卖出区间相加。 */
@Component
public class StrategyReplayComparison {
    public static final List<BigDecimal> INITIAL_RATIOS=List.of(BigDecimal.ZERO,new BigDecimal("0.25"),new BigDecimal("0.50"),new BigDecimal("0.75"),BigDecimal.ONE);
    public record Execution(int requestedTradeDays,int executedTradeDays,int unexecutedTradeDays,double meanInvestedFraction,
                            int pendingCashSessions,int noReportSessions) {}
    public record Control(BigDecimal initialRatio,Result result,Execution execution) {}
    public record Comparison(Result strategy,Execution execution,List<Control> controls,List<String> limitations) {}
    private final StrategyReplayEngine engine;
    public StrategyReplayComparison(StrategyReplayEngine engine) { this.engine=engine; }
    /** 统一构建策略与所有对照，避免调用方传入同名版本但不同费用或净值的结果。 */
    public Comparison run(List<Frame> frames,Config config,String mode,Map<java.time.LocalDate,IssuedDecision> issued) {
        List<Frame> frozen=List.copyOf(frames);
        return compare(engine.run(frozen,config,mode,issued),frozen,config);
    }
    /** 包内组装，不作为读取任意外部结果的接口；业务入口必须走上面的共同计算。 */
    Comparison compare(Result strategy,List<Frame> frames,Config config) {
        if(strategy.curve().size()!=frames.size() || strategy.initialCash().compareTo(config.initialCash())!=0
                || !Objects.equals(strategy.version(),config.version()) || !Objects.equals(strategy.assumption(),config.assumption()))
            throw new IllegalArgumentException("策略与对照的日期、初始资金或费用口径不一致");
        for(int i=0;i<frames.size();i++) if(!strategy.curve().get(i).date().equals(frames.get(i).date()))
            throw new IllegalArgumentException("策略与对照日期不一致");
        List<Control> controls=INITIAL_RATIOS.stream().map(ratio->{
            Result control=engine.runInitialExposureControl(frames,config,ratio);
            return new Control(ratio,control,execution(control));
        }).toList();
        return new Comparison(strategy,execution(strategy),controls,List.of(
                "五档为事前固定初始投入比例，买入后不再平衡；收益会使实际权重变化，平均持仓另列。",
                "费用、到账与分红再投资均沿用所给实验配置，不声称复原真实渠道历史。",
                "卖后上涨、避免下跌逐区间列出，重叠区间不相加冒充组合收益。",
                "未成交日不删样本；缺原始建议保持缺报；单一已知案例不能证明策略有效。"));
    }
    static Execution execution(Result result) {
        Set<String> actions=Set.of("BUY","ADD","REDUCE","SELL");
        Set<java.time.LocalDate> requested=new HashSet<>(),executed=new HashSet<>();
        result.curve().stream().filter(p->p.action()!=null && actions.contains(p.action())).forEach(p->requested.add(p.date()));
        result.trades().forEach(t->executed.add(t.date()));
        Set<java.time.LocalDate> missed=new HashSet<>(requested);missed.removeAll(executed);
        double invested=result.curve().stream().mapToDouble(p->p.equity().signum()==0?0:
                p.equity().subtract(p.availableCash()).subtract(p.receivable()).divide(p.equity(),java.math.MathContext.DECIMAL128).doubleValue()).average().orElseThrow();
        return new Execution(requested.size(),executed.size(),missed.size(),invested,
                (int)result.curve().stream().filter(p->p.receivable().signum()>0).count(),
                (int)result.curve().stream().filter(p->"NO_REPORT".equals(p.action())).count());
    }
}
