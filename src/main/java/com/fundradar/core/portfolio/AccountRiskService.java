package com.fundradar.core.portfolio;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fundradar.core.auth.CurrentUserContext;
import com.fundradar.core.auth.PermissionCode;
import com.fundradar.core.fund.api.FundRiskSummaryResponse;
import com.fundradar.core.integration.ai.AiFundClient;
import com.fundradar.core.simulation.SimulationRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import static com.fundradar.core.portfolio.AccountFundingTypes.Scope;

/** 当前持仓账本为唯一业务来源；历史截图仅留存，不作为独立账户读取。 */
@Service
public class AccountRiskService {
    public record Holding(String fundCode,String fundName,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal amount,LocalDate date,String issue) {}
    public record Exposure(String key,String name,BigDecimal weightPct) {}
    public record Source(String fundCode,String reportDate,String publishedDate,String sourceUrl) {}
    public record Result(Scope scope,LocalDate holdingsDate,String scopeDescription,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal recordedAmount,
            List<Holding> holdings,List<Exposure> industries,List<Exposure> companies,
            BigDecimal coveredFundWeightPct,BigDecimal knownLookThroughWeightPct,List<Source> sources,
            BigDecimal scenarioDeclinePct,@JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal scenarioLoss,
            List<String> limitations) {}
    private final JdbcClient db;
    private final SimulationRepository simulation;
    private final AiFundClient funds;
    public AccountRiskService(JdbcClient db,SimulationRepository simulation,AiFundClient funds) {
        this.db=db;this.simulation=simulation;this.funds=funds;
    }
    public Result read(Scope scope,BigDecimal scenario) {
        UUID user=CurrentUserContext.requirePermission(PermissionCode.PORTFOLIO_SELF_READ).userId();
        if(scenario!=null && (scenario.signum()<0 || scenario.compareTo(new BigDecimal("100"))>0))
            throw new IllegalArgumentException("假设跌幅应在0至100之间。");
        // 当前持仓账本是唯一业务来源；旧scope参数仅兼容历史链接，不再读取截图快照。
        scope=Scope.SIMULATED;
        List<Holding> holdings=simulation.positions(user).stream()
                .filter(p->p.shares()!=null && p.shares().signum()>0)
                .map(p->new Holding(p.fundCode(),p.fundName(),p.marketValue(),p.navDate(),p.issue())).toList();
        if(holdings.size()>1000) throw new IllegalArgumentException("已录入持仓数量超过当前账户检查范围。");
        // 仅查询公共基金事实，不向 Python 发送用户名、份额、金额或资金安排。
        Map<String,FundRiskSummaryResponse> risks=new HashMap<>();
        if(holdings.stream().anyMatch(h->h.fundCode().equals("002112"))) {
            try { risks.put("002112",funds.getFundRiskSummary("002112")); }
            catch(RuntimeException ignored) { /* 公共资料失败保持未知，本人快照仍可读。 */ }
        }
        return calculate(scope,holdings,risks,scenario);
    }
    /** 已知穿透占比不重新拉满；同一股票按代码合并，行业只接受净资产分母。 */
    static Result calculate(Scope scope,List<Holding> holdings,Map<String,FundRiskSummaryResponse> risks,BigDecimal scenario) {
        BigDecimal total=holdings.stream().map(Holding::amount).filter(Objects::nonNull).reduce(BigDecimal.ZERO,BigDecimal::add);
        if(holdings.stream().anyMatch(h->h.amount()!=null && h.amount().signum()<0) || holdings.stream().map(Holding::fundCode).distinct().count()!=holdings.size())
            throw new IllegalArgumentException("本人持仓快照需要重新核对。");
        boolean complete=holdings.stream().allMatch(h->h.amount()!=null);
        List<String> limits=new ArrayList<>();
        if(!complete) limits.add("部分持仓缺少估值，账户总额和整体情景损失暂不可计算。");
        List<Source> sources=new ArrayList<>();
        Map<String,Exposure> industries=new TreeMap<>(),companies=new TreeMap<>();
        BigDecimal covered=BigDecimal.ZERO,known=BigDecimal.ZERO;
        Set<LocalDate> dates=new HashSet<>();holdings.forEach(h->dates.add(h.date()));
        LocalDate date=dates.size()==1 ? dates.iterator().next() : null;
        if(date==null && !holdings.isEmpty()) limits.add("持仓日期未知或不一致，不能视为同一天的完整账户。");
        if(holdings.stream().anyMatch(h->h.issue()!=null)) limits.add("部分持仓估值待核对，金额保留原记录。");
        if(complete && total.signum()>0) for(Holding holding:holdings) {
            var risk=risks.get(holding.fundCode());
            if(risk==null || !holding.fundCode().equals(risk.fundCode()) || !risk.available() || holding.date()==null || holding.issue()!=null) continue;
            try {
                // 用后来披露的持仓解释更早个人快照，会产生未来信息；此处直接拒绝。
                if(LocalDate.parse(risk.publishedDate()).isAfter(holding.date()) || LocalDate.parse(risk.reportDate()).isAfter(holding.date())) continue;
                BigDecimal factor=holding.amount().divide(total,16,RoundingMode.HALF_UP);
                if(risk.holdings()==null || risk.holdings().stream().anyMatch(h->h==null || h.stockCode()==null || h.stockCode().isBlank() || h.weightPct()==null || h.weightPct().signum()<0 || h.weightPct().compareTo(new BigDecimal("100"))>0)) continue;
                BigDecimal disclosed=risk.holdings().stream().map(FundRiskSummaryResponse.Holding::weightPct).reduce(BigDecimal.ZERO,BigDecimal::add);
                if(disclosed.compareTo(new BigDecimal("100"))>0 || risk.holdings().stream().map(FundRiskSummaryResponse.Holding::stockCode).distinct().count()!=risk.holdings().size()) continue;
                covered=covered.add(factor.multiply(new BigDecimal("100")));
                known=known.add(factor.multiply(disclosed));
                for(var h:risk.holdings()) add(companies,h.stockCode(),h.name(),factor.multiply(h.weightPct()));
                if(risk.industries()!=null && risk.industries().stream().allMatch(a->a!=null && a.name()!=null && !a.name().isBlank() && a.weightPct()!=null && a.weightPct().signum()>=0 && "基金净资产".equals(a.denominator()))
                        && risk.industries().stream().map(FundRiskSummaryResponse.Allocation::weightPct).reduce(BigDecimal.ZERO,BigDecimal::add).compareTo(new BigDecimal("100"))<=0)
                    for(var a:risk.industries()) add(industries,a.name(),a.name(),factor.multiply(a.weightPct()));
                sources.add(new Source(holding.fundCode(),risk.reportDate(),risk.publishedDate(),risk.sourceUrl()));
            } catch(java.time.DateTimeException | NullPointerException ignored) { /* 日期无法核验，不用新资料倒推旧账户。 */ }
        }
        if(holdings.isEmpty()) limits.add("该范围尚无本人持仓记录；关注基金不等于持有基金。");
        limits.add("金额与占比仅代表已录入范围；披露持仓不等于当前实际持仓，未覆盖部分保持未知。");
        limits.add("缺少完整资金流水及可比收益序列，未计算历史账户回撤；买入以来盈亏与回撤不是同一个指标。");
        limits.add("情景是对已录入金额统一假设下跌，忽略费用及资金在途，不是走势预测或发生概率。");
        return new Result(scope,date,"我的持仓",holdings.isEmpty() || !complete?null:total,
                holdings,sorted(industries),sorted(companies),holdings.isEmpty() || !complete?null:covered,!complete || covered.signum()==0?null:known,sources,
                scenario,scenario==null || holdings.isEmpty() || !complete?null:total.multiply(scenario).divide(new BigDecimal("100"),2,RoundingMode.HALF_UP),limits);
    }
    private static void add(Map<String,Exposure> result,String key,String name,BigDecimal weight) {
        Exposure old=result.get(key);result.put(key,new Exposure(key,name,old==null?weight:old.weightPct().add(weight)));
    }
    private static List<Exposure> sorted(Map<String,Exposure> map) {
        return map.values().stream().sorted(Comparator.comparing(Exposure::weightPct).reversed().thenComparing(Exposure::key)).toList();
    }
}
