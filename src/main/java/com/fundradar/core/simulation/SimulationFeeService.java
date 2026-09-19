package com.fundradar.core.simulation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.fundradar.core.simulation.SimulationTypes.*;

/** 模拟交易费率配置服务：天天基金 f10 抓取初始化 + 人工维护；只读费率由结算链路共用 loadFees。 */
@Service
public class SimulationFeeService {
    private static final Logger LOGGER=LoggerFactory.getLogger(SimulationFeeService.class);
    /** 全量初始化并发度：限制对天天基金 f10 页面的并发抓取，避免被限流。 */
    private static final int INIT_CONCURRENCY=4;
    private final SimulationRepository repo;
    private final SimulationMarketClient client;
    public SimulationFeeService(SimulationRepository repo,SimulationMarketClient client) { this.repo=repo; this.client=client; }

    public Page<FeeRuleRow> pageRules(String fundCode,int page,int pageSize) {
        return repo.pageRules(fundCode,page,pageSize);
    }
    /** 人工维护费率：先校验费率范围，再按乐观锁版本更新，防止两个管理员互相覆盖。 */
    public FeeRuleRow updateRule(long ruleId,BigDecimal rate,int version) {
        if (rate==null || rate.signum()<0 || rate.compareTo(BigDecimal.ONE)>0) {
            throw new SimulationException("SIM_FEE_RATE_INVALID","费率必须在 0 到 1 之间（0.0015 表示 0.15%）。");
        }
        repo.updateFeeRule(ruleId,rate,version);
        return repo.findFeeRule(ruleId);
    }
    /** 按天天基金 f10 抓取刷新单基金费率，返回刷新后生效中的全部规则。 */
    public List<FeeRuleRow> refreshFund(String code) {
        FundFee fee=client.fetchFees(code);
        repo.upsertFees(fee);
        LOGGER.info("SimulationFeeService.refreshFund   >>> fee rules refreshed, fundCode={}, purchaseRate={}, bands={}",
                code,fee.purchaseRate(),fee.redeemBands().size());
        return repo.pageRules(code,1,50).items().stream().filter(r -> r.effectiveTo()==null).toList();
    }
    /** 全量初始化：并发抓取模拟范围内全部基金的费率；单只失败只记录原因，不中断其余基金。 */
    public FeeInitResult initAll() {
        List<String> codes=repo.simFundCodes();
        if (codes.isEmpty()) return new FeeInitResult(0,0,List.of());
        List<String> failures=new CopyOnWriteArrayList<>();
        AtomicInteger updated=new AtomicInteger();
        var executor=Executors.newFixedThreadPool(INIT_CONCURRENCY);
        try {
            List<Future<Void>> tasks=codes.stream().map(code -> (Callable<Void>)() -> {
                try { repo.upsertFees(client.fetchFees(code)); updated.incrementAndGet(); }
                catch(Exception error) {
                    failures.add(code+"："+error.getMessage());
                    LOGGER.warn("SimulationFeeService.initAll   >>> fee fetch failed, fundCode={}",code,error);
                }
                return null;
            }).map(executor::submit).toList();
            for(Future<?> task : tasks) { try { task.get(); } catch(InterruptedException error) { Thread.currentThread().interrupt(); throw new SimulationException("SIM_FEE_INIT_INTERRUPTED","费率初始化被中断。"); } catch(ExecutionException ignored) { /* 任务内部已记录失败 */ } }
        } finally { executor.shutdown(); }
        LOGGER.info("SimulationFeeService.initAll   >>> fee initialization finished, total={}, updated={}, failed={}",
                codes.size(),updated.get(),failures.size());
        return new FeeInitResult(codes.size(),updated.get(),failures);
    }
}
