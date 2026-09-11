package com.fundradar.core.advice;

import com.fundradar.core.simulation.SimulationCalendar;
import com.fundradar.core.simulation.SimulationTypes.Position;
import com.fundradar.core.watchlist.api.DirectionExperimentResponse;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import static com.fundradar.core.advice.AdviceTypes.*;

/** 固定且可追溯的首版参考规则；不因事后表现挑选模型或调整阈值。 */
@Component
public class AdvicePolicy {
    public static final String VERSION = "HOLDING_ADVICE_V1";
    public Snapshot build(Position position, DirectionExperimentResponse experiment, SimulationCalendar calendar, Instant now) {
        var evidence = new ArrayList<Evidence>();
        var limits = List.of("当前只采用现有实验模型，尚未纳入新闻、公告、政策与行业分析。",
                "两套模型尚未证明稳定优势，分数未经独立校准；此处为初步参考规则。",
                "当前规则未按个人成本、仓位、持有期和申赎费用优化，也不自动执行交易。");
        String issue = null;
        if (position.shares().signum() <= 0) issue = "当前尚无已确认的持有份额，暂不形成继续持有或卖出的建议。";
        else if (position.issue() != null) issue = "持仓数据需要核对：" + position.issue();
        else if (experiment == null || !"EXPERIMENTAL".equals(experiment.status()))
            issue = experiment == null ? "实验模型资料暂时无法读取。" : experiment.message();
        if (issue != null) {
            evidence.add(new Evidence("DATA_STATUS", "CONTEXT", "本次未形成建议的原因", issue, "持仓与模型检查", null, null));
            return new Snapshot(VERSION, "UNAVAILABLE", issue, List.copyOf(evidence), limits, position, experiment, null, null);
        }
        var main = experiment.models().get(0);
        var reference = experiment.models().get(1);
        boolean hold = main.score() > 0.5;
        String decision = hold ? "HOLD" : "SELL";
        String summary = hold
                ? "现有主模型对后续整体回报的判断偏正，按当前参考规则建议继续持有。"
                : "现有主模型对后续整体回报的判断偏弱，按当前参考规则建议卖出。";
        boolean agrees = (reference.score() > 0.5) == hold;
        if (!agrees) summary += "参考模型意见相反，判断存在分歧。";
        evidence.add(new Evidence("MODEL", "SUPPORT", "主模型的判断",
                "主模型当前分数为 " + score(main.score()) + "，对应未来20个交易日现金分红再投回报"
                        + (hold ? "偏正" : "偏非正") + "。它是实验分数，不是已验证的上涨概率。",
                "本地实验模型 · DROP_60D_GROUP_L2", experiment.cutoffDate(), null));
        evidence.add(new Evidence("MODEL", agrees ? "SUPPORT" : "AGAINST", "参考模型的判断",
                "参考模型分数为 " + score(reference.score()) + "，与主模型" + (agrees ? "方向一致。" : "方向相反，构成本次建议的反对依据。"),
                "本地实验模型 · REFERENCE", experiment.cutoffDate(), null));
        evidence.add(new Evidence("RULE", "CONTEXT", "如何形成操作建议",
                "固定使用主模型：分数大于0.5建议继续持有，小于或等于0.5建议卖出；参考模型只提供对照，不根据当天结果换主模型。",
                VERSION, null, null));
        LocalDate start = calendar.tradeDate(now), end = start;
        for (int i=0; i<20; i++) end = calendar.next(end);
        return new Snapshot(VERSION, decision, summary, List.copyOf(evidence), limits, position, experiment, start, end);
    }
    public Snapshot unavailable(Position position, String message) {
        return new Snapshot(VERSION,"UNAVAILABLE",message,
                List.of(new Evidence("DATA_STATUS","CONTEXT","本次未形成建议的原因",message,"后台检查",null,null)),
                List.of("缺少可核验依据时不默认建议继续持有。"),position,null,null,null);
    }
    private String score(double value) { return String.format(Locale.ROOT,"%.1f%%",value*100); }
}
