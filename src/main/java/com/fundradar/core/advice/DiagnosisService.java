package com.fundradar.core.advice;

import com.fundradar.core.auth.CurrentUserContext;
import com.fundradar.core.auth.PermissionCode;
import com.fundradar.core.simulation.SimulationCalendar;
import com.fundradar.core.simulation.SimulationException;
import com.fundradar.core.simulation.SimulationRepository;
import com.fundradar.core.simulation.SimulationTypes;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import static com.fundradar.core.advice.DiagnosisTypes.*;

/** 诊断读取不生成历史；归档用短事务与数据库锁，同日同输入幂等，事实变化追加版本。 */
@Service
public class DiagnosisService {
    private final DiagnosisRepository repo;
    private final SimulationRepository positions;
    private final DiagnosisPolicy policy;
    private final TransactionTemplate tx;
    public DiagnosisService(DiagnosisRepository repo,SimulationRepository positions,DiagnosisPolicy policy,
                            TransactionTemplate tx) {
        this.repo=repo; this.positions=positions; this.policy=policy; this.tx=tx;
    }
    private UUID reader() { return CurrentUserContext.requirePermission(PermissionCode.PORTFOLIO_SELF_READ).userId(); }
    private void owns(UUID user,String code) {
        if(code==null || !code.matches("[0-9]{6}")) throw new SimulationException("SIM_INVALID","基金代码格式不正确。");
        if(!repo.owns(user,code)) throw DiagnosisRepository.notFound();
    }
    public List<Summary> latest() { return repo.latest(reader()); }
    public History history(String code,int page,int size) {
        UUID user=reader(); owns(user,code);
        if(page<1 || page>10000 || size<1 || size>50) throw new SimulationException("SIM_INVALID","分页参数不正确。");
        var last=repo.latest(user,code);
        String name=last==null ? positions.positions(user).stream().filter(p->p.fundCode().equals(code))
                .map(SimulationTypes.Position::fundName).findFirst().orElse(code) : last.fundName();
        return new History(code,name,repo.history(user,code,page,size),
                last==null ? null : repo.detail(user,code,last.reportId()),positions.job("portfolio-advice"));
    }
    /** 后台与手动路径共用；facts为null表示来源失败，七项全部记INSUFFICIENT并写明原因。 */
    public UUID archive(UUID user,SimulationTypes.Position position,Facts facts,Instant now) {
        LocalDate today=now.atZone(SimulationCalendar.ZONE).toLocalDate();
        List<FactItem> items=facts==null ? policy.failureItems()
                : policy.judge(facts,repo.baselineItems(user,position.fundCode(),today),
                        repo.recentRankDays(user,position.fundCode(),today,2));
        String fingerprint=repo.hash(items);
        String verdict=policy.overall(items);
        LocalDate cutoff=facts==null ? null : facts.asOfDate();
        return tx.execute(status-> {
            repo.lock(user,position.fundCode());
            return repo.insert(user,today,now,position.fundCode(),position.fundName(),verdict,cutoff,items,fingerprint);
        });
    }
}
