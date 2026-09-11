package com.fundradar.core.advice;

import com.fundradar.core.auth.CurrentUserContext;
import com.fundradar.core.auth.PermissionCode;
import com.fundradar.core.integration.ai.AiPredictionClient;
import com.fundradar.core.simulation.*;
import com.fundradar.core.watchlist.api.DirectionExperimentResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import static com.fundradar.core.advice.AdviceTypes.*;

/** 外部推理在事务外；归档使用短事务与数据库锁，GET从不生成或修改历史。 */
@Service
public class AdviceService {
    private final AdviceRepository repo;
    private final SimulationRepository positions;
    private final SimulationMarketClient market;
    private final AiPredictionClient predictions;
    private final AdvicePolicy policy;
    private final TransactionTemplate tx;
    private final Clock clock;
    public AdviceService(AdviceRepository repo,SimulationRepository positions,SimulationMarketClient market,
                         AiPredictionClient predictions,AdvicePolicy policy,TransactionTemplate tx,Clock clock) {
        this.repo=repo; this.positions=positions; this.market=market; this.predictions=predictions;
        this.policy=policy; this.tx=tx; this.clock=clock;
    }
    private UUID reader() { return CurrentUserContext.requirePermission(PermissionCode.PORTFOLIO_SELF_READ).userId(); }
    private void owns(UUID user,String code) {
        if(code==null || !code.matches("[0-9]{6}")) throw new SimulationException("SIM_INVALID","基金代码格式不正确。");
        if(!repo.owns(user,code)) throw AdviceRepository.notFound();
    }
    public List<Summary> latest() { return repo.latest(reader()); }
    public History history(String code,int page,int size,LocalDate start,LocalDate end,String version) {
        UUID user=reader(); owns(user,code);
        if(page<1 || page>10000 || size<1 || size>50 || (start!=null && end!=null && start.isAfter(end))
                || version==null || !version.matches("[A-Z0-9_]{1,60}"))
            throw new SimulationException("SIM_INVALID","日期或分页参数不正确。");
        var last=repo.latest(user,code);
        String name=last==null ? positions.positions(user).stream().filter(p->p.fundCode().equals(code))
                .map(SimulationTypes.Position::fundName).findFirst().orElse(code) : last.fundName();
        return new History(code,name,repo.history(user,code,page,size,start,end,version),
                repo.stats(user,code,start,end,version),positions.job("portfolio-advice"));
    }
    public Detail detail(String code,UUID id) { UUID user=reader(); owns(user,code); return repo.detail(user,code,id); }
    public Detail generate(String code) {
        UUID user=CurrentUserContext.requirePermission(PermissionCode.SIM_PORTFOLIO_SELF_WRITE).userId();
        CurrentUserContext.requirePermission(PermissionCode.PORTFOLIO_SELF_READ); owns(user,code);
        Instant now=clock.instant();
        var last=repo.latest(user,code);
        // 用户重复点击最多每两分钟重新检查一次；已保存报告可随时读取。
        if(last!=null && Duration.between(last.generatedAt(),now).toSeconds()<120) return repo.detail(user,code,last.reportId());
        var position=positions.positions(user).stream().filter(p->p.fundCode().equals(code)).findFirst().orElseThrow(AdviceRepository::notFound);
        DirectionExperimentResponse experiment=position.shares().signum()>0 ? predictions.readExperiment(code) : null;
        SimulationCalendar calendar=null;
        try { calendar=new SimulationCalendar(market.calendar()); } catch(SimulationException ignored) { /* 生成带原因的不可用日报。 */ }
        UUID id=archive(user,position,experiment,calendar,clock.instant());
        return repo.detail(user,code,id);
    }
    /** 后台和手动生成共用规则。原样本身份忽略同步水位、读取时间，避免同一分数反复计数。 */
    public UUID archive(UUID user,SimulationTypes.Position position,DirectionExperimentResponse experiment,
                        SimulationCalendar calendar,Instant now) {
        Snapshot draft;
        try {
            draft=calendar==null ? policy.unavailable(position,"交易日历暂时无法读取，无法确定建议的观察区间。")
                    : policy.build(position,experiment,calendar,now);
        } catch(SimulationException error) { draft=policy.unavailable(position,error.getMessage()); }
        Snapshot candidate=draft;
        String fingerprint=repo.fingerprint(candidate);
        String sampleKey="UNAVAILABLE".equals(candidate.decision()) ? null : repo.hash(List.of(position.fundCode(),
                experiment.cutoffDate(),experiment.models().get(0).modelHash(),experiment.models().get(0).score(),
                experiment.models().get(1).score(),AdvicePolicy.VERSION));
        return tx.execute(status->{
            repo.lock(user,position.fundCode());
            Summary original=repo.firstSample(user,position.fundCode(),sampleKey);
            Snapshot snapshot=original==null ? candidate : new Snapshot(candidate.ruleVersion(),candidate.decision(),candidate.summary(),
                    candidate.evidence(),candidate.limitations(),candidate.position(),candidate.experiment(),original.observationStart(),original.observationEnd());
            return repo.insert(user,now.atZone(SimulationCalendar.ZONE).toLocalDate(),now,snapshot,sampleKey,fingerprint,
                    original==null ? null : original.reportId());
        });
    }
}
