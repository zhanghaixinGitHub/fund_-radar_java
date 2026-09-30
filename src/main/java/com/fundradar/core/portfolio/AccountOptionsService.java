package com.fundradar.core.portfolio;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fundradar.core.auth.CurrentUserContext;
import com.fundradar.core.auth.PermissionCode;
import com.fundradar.core.notification.service.NotificationNotFoundException;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.fundradar.core.portfolio.AccountFundingTypes.*;

/** 事实与条件核对独立于旧预测决策。不调用旧决策的默认偏好，不把费用实验假设当成真实渠道规则。 */
@Service
public class AccountOptionsService {
    public record Path(String title,String status,List<String> conditions,List<String> supportingReasons,List<String> opposingReasons,
                       List<String> costs,List<String> reviewConditions,List<String> reentryConditions,
                       LocalDate earliestExecutionDate,@JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal executableShares,
                       @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal estimatedFee,List<String> missing) {}
    public record Result(Scope scope,String fundCode,String fundName,LocalDate holdingsDate,LocalDate checkedOn,
                         String conclusion,String advice,List<String> facts,List<String> personalChecks,
                         List<Path> paths,List<String> invalidationConditions,List<String> limitations) {}
    private final AccountRiskService risk;
    private final AccountFundingRepository funding;
    private final Clock clock;
    public AccountOptionsService(AccountRiskService risk,AccountFundingRepository funding,Clock clock) { this.risk=risk;this.funding=funding;this.clock=clock; }
    public Result read(Scope scope,String code,BigDecimal hypotheticalDeclinePct) {
        UUID user=CurrentUserContext.requirePermission(PermissionCode.PORTFOLIO_SELF_READ).userId();
        if(code==null || !code.matches("[0-9]{6}")) throw new IllegalArgumentException("请选择已录入的基金。");
        var account=risk.read(scope,hypotheticalDeclinePct);
        var holding=account.holdings().stream().filter(h->h.fundCode().equals(code)).findFirst().orElseThrow(NotificationNotFoundException::new);
        LocalDate today=clock.instant().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
        return assemble(account,holding,funding.current(user,scope),today);
    }
    /** 候选路径列出完整前后条件，明确停在资料核对，不输出未验证的精确金额、成交日或买卖建议。 */
    static Result assemble(AccountRiskService.Result account,AccountRiskService.Holding holding,View preference,LocalDate today) {
        List<String> facts=new ArrayList<>();
        facts.add((account.scope()==Scope.CONFIRMED?"本人确认持仓资料":"模拟记录")+"包含该基金，持仓日期"+(holding.date()==null?"未知":holding.date())+"。");
        account.sources().stream().filter(s->s.fundCode().equals(holding.fundCode())).forEach(s->facts.add("可对照披露持仓日"+s.reportDate()+"，公开日"+s.publishedDate()+"；披露不代表当前实际持仓。"));
        List<String> gaps=new ArrayList<>();
        if(holding.date()==null || holding.issue()!=null) gaps.add("持仓日期或估值尚未核对。");
        if(preference==null || !"ACTIVE".equals(preference.status())) gaps.add("该范围尚无本人确认的资金使用和承受条件。");
        gaps.add("真实渠道的申赎开放状态、截止规则、确认及到账日期尚未核对。");
        gaps.add("可操作份额、各持有批次及相应费用依据尚未核对；实际费用须以所用渠道和持有时间为准。");
        gaps.add("退出、最短观察间隔和重新进入条件尚无适用的完整效果验证。");
        List<String> review=List.of("本人用款安排或现实承受能力变化时重新核对。","基金相关事实发生实质更正、持仓披露或交易限制变化时重新核对。","普通涨跌或概率小幅变化不单独触发反向操作。");
        List<String> reentry=List.of("卖出款已实际到账，拟投入金额不占用本人已确认的用款安排。","基金与所用渠道开放申购，费用、确认日及可用日可核对。","此前退出原因已获得可追溯的新证据证明改善，整体配置仍适合本人条件。","满足预先验证的最短观察间隔和重新进入条件；这些条件当前未定稿，不能临时追涨。","重大新事实需要改变原条件时，先保存变更原因和原规则，不能覆盖历史。");
        List<Path> paths=List.of(
            new Path("保持现有持仓","待核对",List.of("资金使用日期、可承担损失和现有暴露相容。"),
                    List.of("此路径避免新增赎回与再次申购操作，是比较其他路径的参照。"),
                    List.of("继续承担该基金的波动；用款将到期或承受条件不符时，不能默认继续持有。"),
                    List.of("若实际不交易，不新增本次申赎费用；基金净值内已计费用不重复扣除。"),review,List.of(),null,null,null,List.copyOf(gaps)),
            new Path("暂停新增后复查","待核对",List.of("先核实是否存在新增或定投安排，并单独确认变更。"),
                    List.of("此路径暂不扩大已录入暴露，给资料核对保留时间。"),List.of("既有持仓仍可能下跌，也可能错过后续上涨。"),
                    List.of("需要核对未确认申购、撤单及计划条款；本页面不改变已有定投。"),review,reentry,null,null,null,List.copyOf(gaps)),
            new Path("减少部分持有后复查","待核对",List.of("有可核对的本人约束或重要事实支持减少，并能确认可卖份额。"),
                    List.of("此路径可减少对应的后续波动暴露；减少比例须另有依据。"),List.of("保留部分仓位仍承担下跌，减少部分也可能错过上涨。"),
                    List.of("按真实持有批次核算赎回费、资金在途、后续买回费和错过机会；现有资料不足以计算。"),review,reentry,null,null,null,List.copyOf(gaps)),
            new Path("退出后按条件重新进入","待核对",List.of("退出依据、可卖份额、最早成交日及到账约束均可核对。","先确认用款限制，后讨论是否和何时重新进入。"),
                    List.of("此路径可在实际卖出后停止承担相应基金的持仓波动。"),List.of("卖出前仍有净值风险；卖后上涨、资金未到账和申购限制均可能阻碍买回。"),
                    List.of("完整计算卖出及买回费用、资金在途、空仓时间和踏空；不能把卖后最低价当作必然可买回价。"),review,reentry,null,null,null,List.copyOf(gaps)));
        return new Result(account.scope(),holding.fundCode(),holding.fundName(),holding.date(),today,
                "当前可比较条件与代价，尚不足以形成明确买卖建议。",null,List.copyOf(facts),personalChecks(preference,account,today),paths,
                List.of("以上依据本次已录入资料；资金安排、持仓或相关事实变化后需重新核对。","交易状态未核验，因此没有可执行金额、份额、最早成交日期或承诺有效期。"),
                List.of("候选路径并非已验证的择时策略，也不执行交易。","本人确认持仓与模拟组合分别核对；实际费用以所用渠道规则为准。","资料不足时暂不提供操作建议。"));
    }
    /** 只比较本人已确认条件；愿意承受的百分比和现实可承担金额分别呈现，不互相替代。 */
    static List<String> personalChecks(View preference,AccountRiskService.Result account,LocalDate today) {
        if(preference==null || !"ACTIVE".equals(preference.status()) || preference.input()==null)
            return List.of("该范围没有已确认生效的资金安排；不能推定用款期限或损失承受能力。");
        Input value=preference.input();List<String> out=new ArrayList<>();
        if(value.useDate()!=null) out.add("预计用款日"+value.useDate()+(today.isBefore(value.useDate())?"，仍需核对真实到账期限。":"已到达，需优先复查资金是否可按时使用；不据此自动卖出。"));
        else out.add("预计用款日期未知，不能承诺退出和买回时间合适。");
        if(value.requiredAmount()!=null && account.recordedAmount()!=null)
            out.add(value.requiredAmount().compareTo(account.recordedAmount())>0?"预计用款金额高于已录入金额；其他资产和现金未录全，不能把差额当成实际资金缺口。":"已录入金额不小于预计用款金额，但不等于已有可立即使用的现金。");
        else out.add("预计用款金额或已录入金额不完整，现金覆盖情况未知。");
        if(value.willingLossPct()!=null) out.add("愿意接受的损失比例为"+value.willingLossPct().toPlainString()+"%；历史账户回撤不可比，尚不能核对是否达到。");
        else out.add("心理上愿意接受的损失比例尚未确定。");
        if(value.affordableLossAmount()==null) out.add("现实资金可承担的损失金额尚未确定。");
        else if(account.scenarioLoss()==null) out.add("现实可承担损失已确认；尚未选择假设跌幅，不推断损失或触发条件。");
        else out.add("在整体假设下跌"+account.scenarioDeclinePct().toPlainString()+"%时，已录入范围损失"+account.scenarioLoss().toPlainString()+"元，"+
                    (account.scenarioLoss().compareTo(value.affordableLossAmount())>0?"高于":"不高于")+"本人现实可承担金额；这不是发生概率、实际回撤或买卖建议。");
        return List.copyOf(out);
    }
}
