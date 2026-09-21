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

    /** 全量同步沿用模拟范围，只向行情任务返回基金代码，不暴露个人关系。 */
    public List<String> fundCodes() { return repo.simFundCodes(); }

    /** 校验来自行情任务的完整档案，只有比例与连续天数分档合法才替换旧规则。 */
    public void saveFetchedProfile(FundFee fee) {
        if (fee==null || fee.fundCode()==null || !fee.fundCode().matches("[0-9]{6}")
                || fee.fundName()==null || fee.fundName().isBlank() || fee.fundName().length()>256
                || !"EASTMONEY_F10".equals(fee.dataSource()) || !validRate(fee.purchaseRate())
                || !validRate(fee.purchaseOriginalRate())
                || (fee.discountInfo()!=null && fee.discountInfo().length()>64)
                || fee.redeemBands()==null || fee.redeemBands().isEmpty() || fee.redeemBands().size()>100) {
            throw new IllegalArgumentException("费率档案不完整或数值无效，保留原规则。");
        }
        long next=0;
        for (int i=0; i<fee.redeemBands().size(); i++) {
            FeeBand band=fee.redeemBands().get(i);
            boolean last=i==fee.redeemBands().size()-1;
            if (band==null || band.minDays()!=next || !validRate(band.rate())
                    || (last ? band.maxDays()!=null : band.maxDays()==null || band.maxDays()<band.minDays())) {
                throw new IllegalArgumentException("赎回费率分档不连续，保留原规则。");
            }
            if (band.maxDays()!=null) next=(long)band.maxDays()+1;
        }
        repo.upsertFees(fee);
    }

    private boolean validRate(BigDecimal rate) {
        return rate!=null && rate.signum()>=0 && rate.compareTo(BigDecimal.ONE)<=0;
    }

    /** 查询只读取已保存费率；关键词去除首尾空格，空值查全部，长度与市场搜索框一致。 */
    public Page<FeeRuleRow> pageRules(String fundCode,String keyword,int page,int pageSize) {
        String search=keyword==null ? null : keyword.strip();
        if (search!=null && search.length()>50) throw new IllegalArgumentException("基金查询关键词不能超过 50 个字符。");
        if (search!=null && search.isEmpty()) search=null;
        return repo.pageRules(fundCode,search,page,pageSize);
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
