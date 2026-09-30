package com.fundradar.core.portfolio;

import com.fundradar.core.auth.CurrentUserContext;
import com.fundradar.core.auth.PermissionCode;
import com.fundradar.core.simulation.SimulationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import static com.fundradar.core.portfolio.AccountFundingTypes.*;

/** 不发送个人数据给 Python 或外部服务；本人确认后生效，读取不产生默认设置。 */
@Service
public class AccountFundingService {
    private static final org.slf4j.Logger LOGGER=org.slf4j.LoggerFactory.getLogger(AccountFundingService.class);
    private final AccountFundingRepository repo;
    private final TransactionTemplate tx;
    public AccountFundingService(AccountFundingRepository repo,TransactionTemplate tx) { this.repo=repo;this.tx=tx; }
    private UUID reader() { return CurrentUserContext.requirePermission(PermissionCode.PORTFOLIO_SELF_READ).userId(); }
    public Page read(Scope scope,int page) {
        UUID user=reader();
        if(scope==null || page<1 || page>10000) throw new IllegalArgumentException("请选择有效的持仓范围和页码。");
        List<View> items=repo.history(user,scope,page);
        return new Page(scope,repo.current(user,scope),items.stream().limit(20).toList(),items.size()>20);
    }
    /** 复用本人提醒设置写权限；拥有公共基金读权限或管理他人权限均不能代填。 */
    public View change(Scope scope,Change change,boolean revoke) {
        UUID user=reader();
        CurrentUserContext.requirePermission(PermissionCode.ALERT_RULE_SELF_WRITE);
        if(scope==null || change==null || change.requestId()==null || change.expectedRevision()<0 || !change.confirmed())
            throw new IllegalArgumentException("请核对后明确确认本次设置。");
        Input input=revoke ? null : validate(change.input());
        String status=revoke ? "REVOKED" : "ACTIVE";
        String hash=repo.hash(java.util.Arrays.asList(scope,status,input,change.expectedRevision()));
        View saved=tx.execute(transaction->{
            repo.lock(user);
            var existing=repo.request(user,change.requestId());
            if(existing!=null) {
                if(!hash.equals(existing.hash())) throw new SimulationException("ACCOUNT_REQUEST_CHANGED","该次确认内容已变化，请重新核对。");
                return existing.view();
            }
            View current=repo.current(user,scope);
            long revision=current==null ? 0 : current.revision();
            if(revision!=change.expectedRevision()) throw new SimulationException("ACCOUNT_CHANGED","设置已更新，请刷新后重新确认。");
            if(revoke && (current==null || !"ACTIVE".equals(current.status())))
                throw new SimulationException("ACCOUNT_NOT_ACTIVE","当前没有需要撤销的安排。");
            return repo.insert(user,scope,revision+1,status,change.requestId(),hash,input);
        });
        LOGGER.info("AccountFundingService.change   >>> 本人安排确认完成, scope={}, recordId={}, status={}",scope,saved.preferenceId(),saved.status());
        return saved;
    }
    static Input validate(Input input) {
        if(input==null) throw new IllegalArgumentException("请填写或明确保留未知的资金安排。");
        String purpose=input.purpose()==null ? null : input.purpose().strip();
        if(purpose!=null && (purpose.length()>160 || purpose.codePoints().anyMatch(Character::isISOControl)))
            throw new IllegalArgumentException("资金用途请填写不超过160字的单行说明。");
        if(purpose!=null && purpose.isEmpty()) purpose=null;
        money(input.requiredAmount()); money(input.affordableLossAmount());
        BigDecimal pct=input.willingLossPct();
        if(pct!=null && (pct.signum()<0 || pct.compareTo(new BigDecimal("100"))>0 || pct.scale()>2))
            throw new IllegalArgumentException("愿意接受的损失比例应在0至100之间，最多两位小数。");
        if(input.reviewIntervalDays()!=null && (input.reviewIntervalDays()<1 || input.reviewIntervalDays()>365))
            throw new IllegalArgumentException("定期复查间隔应为1至365天，也可以留空。");
        if(input.useDate()!=null && (input.useDate().getYear()<1900 || input.useDate().getYear()>2200))
            throw new IllegalArgumentException("请填写有效的预计用款日期。");
        return new Input(purpose,input.useDate(),input.requiredAmount(),pct,input.affordableLossAmount(),input.reviewIntervalDays());
    }
    private static void money(BigDecimal value) {
        if(value!=null && (value.signum()<0 || value.scale()>2 || value.compareTo(new BigDecimal("9999999999999999.99"))>0))
            throw new IllegalArgumentException("金额须为非负人民币元，最多两位小数。");
    }
}
