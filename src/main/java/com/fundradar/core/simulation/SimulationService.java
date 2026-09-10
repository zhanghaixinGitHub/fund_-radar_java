package com.fundradar.core.simulation;

import com.fundradar.core.auth.CurrentUserContext;
import com.fundradar.core.auth.PermissionCode;
import jakarta.validation.constraints.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import static com.fundradar.core.simulation.SimulationTypes.*;
import static com.fundradar.core.simulation.SimulationAccounting.ZERO;

/** 本人模拟买卖与定投业务；公共行情在事务外读取，事务内统一锁定用户账本。 */
@Service
public class SimulationService {
    public static final String RULES="模拟交易：人民币场外净值基金，沪深开市日日历，15:00 截止，最早下一交易日确认，确认后可卖；未计申赎手续费，现金分红，先进先出；未模拟渠道限购和临时暂停。";
    public record OrderRequest(@NotNull UUID requestKey,@NotBlank @Pattern(regexp="[0-9]{6}") String fundCode,
                               @NotBlank @Pattern(regexp="BUY|SELL") String side,
                               @DecimalMin("0.01") @DecimalMax("100000000") @Digits(integer=9,fraction=2) BigDecimal amount,
                               @DecimalMin("0.00000001") @DecimalMax("100000000000") @Digits(integer=12,fraction=8) BigDecimal shares,
                               boolean pausePlan) {}
    public record PlanRequest(@NotNull UUID requestKey,@NotBlank @Pattern(regexp="[0-9]{6}") String fundCode,
                              @NotNull @DecimalMin("0.01") @DecimalMax("100000000") @Digits(integer=9,fraction=2) BigDecimal amount,
                              @NotBlank @Pattern(regexp="DAILY|WEEKLY|MONTHLY") String frequency,
                              @Min(1) @Max(31) int dayValue,@NotNull LocalDate startDate,LocalDate endDate,
                              @Min(1) @Max(10000) Integer maxPeriods,@Min(0) int version) {}
    public record PlanAction(@NotBlank @Pattern(regexp="PAUSE|RESUME|END") String action,@Min(1) int version) {}
    private final SimulationRepository repo;
    private final SimulationMarketClient marketClient;
    private final TransactionTemplate tx;
    private final Clock clock;
    public SimulationService(SimulationRepository repo,SimulationMarketClient marketClient,TransactionTemplate tx,Clock clock) {
        this.repo=repo; this.marketClient=marketClient; this.tx=tx; this.clock=clock;
    }
    private UUID reader() { return CurrentUserContext.requirePermission(PermissionCode.PORTFOLIO_SELF_READ).userId(); }
    private UUID writer() { return CurrentUserContext.requirePermission(PermissionCode.SIM_PORTFOLIO_SELF_WRITE).userId(); }
    private UUID planner() { return CurrentUserContext.requirePermission(PermissionCode.SIM_PLAN_SELF_WRITE).userId(); }
    public Overview overview() {
        UUID user=reader();
        var positions=repo.positions(user);
        // 汇总待确认金额不依赖当前分页，避免大额待处理交易被分页隐藏。
        var totals=repo.pendingTotals(user);
        var valued=positions.stream().filter(p -> p.shares().signum()>0).toList();
        var dates=valued.stream().map(Position::navDate).filter(Objects::nonNull).distinct().toList();
        boolean complete=positions.stream().noneMatch(p -> p.issue()!=null) && valued.stream().allMatch(p -> p.navDate()!=null) && dates.size()<=1;
        return new Overview(positions,sum(positions,Position::marketValue),sum(positions,Position::holdingGain),
                sum(positions,Position::cumulativeGain),totals.amount(),totals.count(),complete,repo.job("settlement"),RULES);
    }
    private BigDecimal sum(List<Position> positions,java.util.function.Function<Position,BigDecimal> field) {
        return positions.stream().map(field).reduce(ZERO,BigDecimal::add);
    }
    public Preview preview(String code) {
        UUID user=reader(); validCode(code);
        Instant now=clock.instant(); var calendar=new SimulationCalendar(marketClient.calendar());
        LocalDate today=calendar.today(now);
        Market market=marketClient.market(code,today.minusDays(35),today);
        var last=market.navs().isEmpty() ? null : market.navs().get(market.navs().size()-1);
        var position=repo.positions(user).stream().filter(p -> p.fundCode().equals(code)).findFirst().orElse(null);
        LocalDate trade=calendar.tradeDate(now);
        String issue=position==null ? null : position.issue();
        return new Preview(code,market.fundName(),market.supported() && issue==null,issue!=null ? issue : market.reason(),
                last==null ? null : last.unitNav(),last==null ? null : last.navDate(),trade,calendar.next(trade),
                position==null ? ZERO : position.availableShares(),repo.pendingBuys(user,code),RULES);
    }
    public Order place(OrderRequest request) {
        UUID user=writer(); validateOrder(request);
        String fingerprint=orderHash(request);
        Order repeated=repo.findRequest(user,request.requestKey().toString());
        if (repeated!=null) return matching(repeated,fingerprint);
        Instant now=clock.instant(); var calendar=new SimulationCalendar(marketClient.calendar());
        LocalDate today=calendar.today(now);
        var market=marketClient.market(request.fundCode(),today.minusDays(35),today);
        if (!market.supported()) throw new SimulationException("SIM_UNSUPPORTED",market.reason());
        LocalDate trade=calendar.tradeDate(now),eligible=calendar.next(trade);
        return tx.execute(status -> {
            repo.lock(user);
            var duplicate=repo.findRequest(user,request.requestKey().toString());
            if (duplicate!=null) return matching(duplicate,fingerprint);
            Position position=repo.positions(user).stream().filter(p -> p.fundCode().equals(request.fundCode())).findFirst().orElse(null);
            if (position!=null && position.issue()!=null) throw new SimulationException("SIM_DATA_REVIEW",position.issue());
            if (request.side().equals("SELL") && (position==null || request.shares().compareTo(position.availableShares())>0)) {
                throw new SimulationException("SIM_INSUFFICIENT_SHARES","可卖份额不足，待确认买入及冻结份额不能卖出。");
            }
            Order order=new Order(UUID.randomUUID(),user,request.fundCode(),market.fundName(),request.side(),request.amount(),request.shares(),
                    trade,eligible,"PENDING","MANUAL",request.requestKey().toString(),fingerprint,now,null,null);
            repo.insertOrder(order);
            if (request.side().equals("SELL") && request.pausePlan()) {
                CurrentUserContext.requirePermission(PermissionCode.SIM_PLAN_SELF_WRITE);
                for (var plan : repo.plans(user)) if (plan.fundCode().equals(request.fundCode()) && plan.status().equals("ACTIVE")) {
                    var paused=copyPlan(plan,plan.scheduledDate(),plan.executionDate(),"PAUSED"); repo.savePlan(paused);
                    repo.ledger(user,plan.fundCode(),"plan:"+plan.planId()+":"+paused.version(),"PLAN_PAUSED",paused,now);
                }
            }
            repo.audit(user,"SIM_ORDER_CREATED",order.orderId().toString());
            return order;
        });
    }
    public Order cancel(UUID id) {
        UUID user=writer();
        return tx.execute(status -> {
            repo.lock(user); Order order=repo.order(user,id);
            if (order.status().equals("CANCELLED")) return order;
            if (!order.status().equals("PENDING") || !clock.instant().isBefore(order.tradeDate().atTime(15,0).atZone(SimulationCalendar.ZONE).toInstant())) {
                throw new SimulationException("SIM_CANCEL_CLOSED","已超过撤单截止时间，或该订单已经确认。");
            }
            repo.cancel(order,clock.instant()); repo.audit(user,"SIM_ORDER_CANCELLED",id.toString());
            return repo.order(user,id);
        });
    }
    public Page<Order> orders(int page,int size,String code) { validatePage(page,size); if(code!=null) validCode(code); return repo.orderPage(reader(),page,size,code); }
    public Page<Map<String,Object>> ledger(int page,int size,String code) { validatePage(page,size); if(code!=null) validCode(code); return repo.ledgerPage(reader(),page,size,code); }
    public List<Daily> performance(String code,LocalDate start,LocalDate end) {
        UUID user=reader(); validCode(code);
        if (start==null || end==null || start.isAfter(end) || java.time.temporal.ChronoUnit.DAYS.between(start,end)>3660) throw new IllegalArgumentException("收益日期范围最多十年。");
        return repo.performance(user,code,start,end);
    }
    public List<Plan> plans() { return repo.plans(reader()); }
    public Page<Period> periods(UUID id,int page,int size) { validatePage(page,size); return repo.periods(reader(),id,page,size); }
    public Map<String,LocalDate> planPreview(PlanRequest request) {
        planner(); validatePlan(request);
        var calendar=new SimulationCalendar(marketClient.calendar());
        LocalDate scheduled=calendar.scheduled(request.frequency(),request.dayValue(),calendar.firstPlanBase(request.startDate(),clock.instant()));
        LocalDate execution=calendar.atOrAfter(scheduled);
        if (request.endDate()!=null && execution.isAfter(request.endDate())) throw new IllegalArgumentException("结束日期早于第一期可执行日期。");
        calendar.next(execution);
        return Map.of("scheduledDate",scheduled,"executionDate",execution);
    }
    public Plan savePlan(UUID id,PlanRequest request) {
        UUID user=planner(); validatePlan(request);
        var dates=planPreview(request); Instant now=clock.instant();
        LocalDate today=now.atZone(SimulationCalendar.ZONE).toLocalDate();
        Market market=marketClient.market(request.fundCode(),today.minusDays(35),today);
        if(!market.supported()) throw new SimulationException("SIM_UNSUPPORTED",market.reason());
        return tx.execute(status -> {
            repo.lock(user); var plans=repo.plans(user);
            Plan old=id==null ? null : repo.plan(user,id);
            if (old==null) {
                var repeated=plans.stream().filter(p -> p.requestKey().equals(request.requestKey())).findFirst().orElse(null);
                if (repeated!=null) {
                    if (!samePlan(repeated,request)) throw new SimulationException("SIM_DUPLICATE_KEY","该请求标识已用于另一份计划。");
                    return repeated;
                }
                if(plans.size()>=500) throw new SimulationException("SIM_PLAN_LIMIT","当前账户定投计划已达上限，请整理历史计划。");
                if(plans.stream().anyMatch(p -> p.fundCode().equals(request.fundCode()) && !p.status().equals("ENDED"))) {
                    throw new SimulationException("SIM_PLAN_EXISTS","该基金已有定投计划，请修改或恢复原计划。");
                }
            } else {
                if(old.version()!=request.version()) throw conflict();
                if(old.status().equals("ENDED")) throw new SimulationException("SIM_PLAN_ENDED","已结束的计划不能修改，请新建计划。");
                if(!old.fundCode().equals(request.fundCode())) throw new IllegalArgumentException("已有计划不能改为另一只基金。");
                if(request.maxPeriods()!=null && request.maxPeriods()<=old.orderedPeriods()) throw new IllegalArgumentException("目标期数不能小于或等于已执行期数。");
            }
            Plan saved=new Plan(old==null ? UUID.randomUUID() : old.planId(),user,request.fundCode(),market.fundName(),request.amount(),
                    request.frequency(),request.dayValue(),request.startDate(),request.endDate(),request.maxPeriods(),dates.get("scheduledDate"),
                    dates.get("executionDate"),old==null ? "ACTIVE" : old.status(),old==null ? 1 : old.version()+1,
                    old==null ? request.requestKey() : old.requestKey(),old==null ? now : old.createdAt(),old==null ? 0 : old.orderedPeriods(),old==null ? ZERO : old.investedAmount());
            repo.savePlan(saved); repo.ledger(user,saved.fundCode(),"plan:"+saved.planId()+":"+saved.version(),"PLAN_SAVED",saved,now);
            repo.audit(user,"SIM_PLAN_SAVED",saved.planId().toString()); return saved;
        });
    }
    public Plan planAction(UUID id,PlanAction request) {
        UUID user=planner();
        SimulationCalendar calendar=request.action().equals("RESUME") ? new SimulationCalendar(marketClient.calendar()) : null;
        return tx.execute(status -> {
            repo.lock(user); Plan old=repo.plan(user,id);
            if(old.version()!=request.version()) throw conflict();
            if(old.status().equals("ENDED")) throw new SimulationException("SIM_PLAN_ENDED","该计划已经结束。");
            String state=switch(request.action()) { case "PAUSE" -> "PAUSED"; case "RESUME" -> "ACTIVE"; case "END" -> "ENDED"; default -> throw new IllegalArgumentException("计划操作无效。"); };
            LocalDate scheduled=old.scheduledDate(),execution=old.executionDate();
            if(state.equals("ACTIVE")) {
                if(old.maxPeriods()!=null && old.orderedPeriods()>=old.maxPeriods()) throw new IllegalArgumentException("计划已经达到设定期数。");
                scheduled=calendar.scheduled(old.frequency(),old.dayValue(),calendar.firstPlanBase(old.startDate(),clock.instant()));
                execution=calendar.atOrAfter(scheduled);
                if(old.endDate()!=null && execution.isAfter(old.endDate())) throw new IllegalArgumentException("计划已超过结束日期。");
                calendar.next(execution);
            }
            Plan saved=copyPlan(old,scheduled,execution,state); repo.savePlan(saved);
            repo.ledger(user,saved.fundCode(),"plan:"+id+":"+saved.version(),"PLAN_"+state,saved,clock.instant());
            repo.audit(user,"SIM_PLAN_"+state,id.toString()); return saved;
        });
    }

    /** 仅由后台调度调用；逐用户加锁，不伪造浏览器身份。 */
    void processUser(UUID user,SimulationCalendar calendar,Map<String,Market> markets,Instant now) {
        tx.executeWithoutResult(status -> {
            repo.lock(user);
            for(var plan : repo.plans(user)) if(plan.status().equals("ACTIVE")) executePlan(plan,calendar,markets.get(plan.fundCode()),now);
            var codes=new LinkedHashSet<String>(); repo.positions(user).forEach(p -> codes.add(p.fundCode()));
            for(String code : codes) {
                Market market=markets.get(code);
                if(market==null) { repo.issue(user,code,"行情服务暂不可用，保留上次估值。",now); continue; }
                try {
                    List<Order> orders=repo.orders(user,code);
                    Calculation calculation=SimulationAccounting.calculate(code,market.fundName(),orders,market,calendar,calendar.today(now));
                    repo.saveCalculation(user,calculation,now);
                } catch(SimulationException error) { repo.issue(user,code,error.getMessage(),now); }
            }
        });
    }
    private void executePlan(Plan initial,SimulationCalendar calendar,Market market,Instant now) {
        Plan plan=initial; LocalDate today=calendar.today(now),lastExecuted=null;
        for(int i=0;i<4000 && plan.status().equals("ACTIVE") && !plan.executionDate().isAfter(today);i++) {
            if(plan.endDate()!=null && plan.executionDate().isAfter(plan.endDate()) || plan.maxPeriods()!=null && plan.orderedPeriods()>=plan.maxPeriods()) {
                repo.savePlan(copyPlan(plan,plan.scheduledDate(),plan.executionDate(),"ENDED")); return;
            }
            if(plan.executionDate().equals(today) && now.atZone(SimulationCalendar.ZONE).toLocalTime().isBefore(LocalTime.of(10,0))) return;
            String state,message; UUID orderId=null;
            if(plan.executionDate().equals(lastExecuted)) { state="SKIPPED"; message="同一计划多个期次顺延到同一天，合并为一次投入。"; }
            else if(plan.executionDate().isBefore(today) || !now.isBefore(calendar.cutoff(plan.executionDate()))) {
                state="MISSED"; message="后台错过本期截止时间，未按历史净值补买。";
            } else if(market==null) {
                // 仍在截止前，保留本期供下一轮重试；截止后将明确记为错过。
                return;
            } else if(!market.supported()) { state="SKIPPED"; message=market.reason(); }
            else {
                if(repo.positions(plan.userId()).stream().anyMatch(p -> p.fundCode().equals(initial.fundCode()) && p.issue()!=null)) return;
                LocalDate eligible;
                try { eligible=calendar.next(plan.executionDate()); }
                catch(SimulationException error) {
                    var paused=copyPlan(plan,plan.scheduledDate(),plan.executionDate(),"PAUSED"); repo.savePlan(paused);
                    repo.ledger(plan.userId(),plan.fundCode(),"calendar:"+plan.planId()+":"+paused.version(),"PLAN_CALENDAR_PAUSED",Map.of("message",error.getMessage()),now); return;
                }
                String key="plan:"+plan.planId()+":"+plan.scheduledDate();
                Order existing=repo.findRequest(plan.userId(),key);
                orderId=existing==null ? UUID.randomUUID() : existing.orderId();
                if(existing==null) repo.insertOrder(new Order(orderId,plan.userId(),plan.fundCode(),plan.fundName(),"BUY",plan.amount(),null,
                        plan.executionDate(),eligible,"PENDING","RECURRING",key,repo.hash(key),now,null,null));
                state="ORDERED"; message="已生成模拟买单，等待净值与确认日期。"; lastExecuted=plan.executionDate();
            }
            repo.period(plan,orderId,state,message,now);
            long count=plan.orderedPeriods()+(state.equals("ORDERED") ? 1 : 0);
            try {
                LocalDate scheduled=calendar.nextScheduled(plan.frequency(),plan.dayValue(),plan.scheduledDate());
                LocalDate execution=calendar.atOrAfter(scheduled);
                String nextState=(plan.maxPeriods()!=null && count>=plan.maxPeriods()) || (plan.endDate()!=null && execution.isAfter(plan.endDate())) ? "ENDED" : "ACTIVE";
                plan=new Plan(plan.planId(),plan.userId(),plan.fundCode(),plan.fundName(),plan.amount(),plan.frequency(),plan.dayValue(),plan.startDate(),
                        plan.endDate(),plan.maxPeriods(),scheduled,execution,nextState,plan.version()+1,plan.requestKey(),plan.createdAt(),count,plan.investedAmount());
                repo.savePlan(plan);
            } catch(SimulationException error) {
                var paused=copyPlan(plan,plan.scheduledDate(),plan.executionDate(),"PAUSED"); repo.savePlan(paused);
                repo.ledger(plan.userId(),plan.fundCode(),"calendar:"+plan.planId()+":"+paused.version(),"PLAN_CALENDAR_PAUSED",Map.of("message",error.getMessage()),now); return;
            }
        }
    }
    private Plan copyPlan(Plan p,LocalDate scheduled,LocalDate execution,String status) {
        return new Plan(p.planId(),p.userId(),p.fundCode(),p.fundName(),p.amount(),p.frequency(),p.dayValue(),p.startDate(),p.endDate(),p.maxPeriods(),
                scheduled,execution,status,p.version()+1,p.requestKey(),p.createdAt(),p.orderedPeriods(),p.investedAmount());
    }
    private void validateOrder(OrderRequest r) {
        validCode(r.fundCode());
        if(r.requestKey()==null || r.side()==null || !Set.of("BUY","SELL").contains(r.side())) throw new IllegalArgumentException("买卖参数不合法。");
        if(r.side().equals("BUY")) {
            if(r.amount()==null || r.amount().signum()<=0 || r.shares()!=null) throw new IllegalArgumentException("买入需填写金额，不能同时填写份额。");
            validateAmount(r.amount());
        } else if(r.shares()==null || r.shares().signum()<=0 || r.amount()!=null) throw new IllegalArgumentException("卖出需填写份额，不能同时填写金额。");
        else if(r.shares().stripTrailingZeros().scale()>8 || r.shares().compareTo(new BigDecimal("100000000000"))>0) throw new IllegalArgumentException("卖出份额最多八位小数且不能超过一千亿份。");
    }
    private void validatePlan(PlanRequest r) {
        validCode(r.fundCode());
        if(r.amount()==null || r.amount().signum()<=0 || r.startDate()==null || r.requestKey()==null) throw new IllegalArgumentException("请完整填写定投计划。");
        validateAmount(r.amount());
        if(r.maxPeriods()!=null && (r.maxPeriods()<1 || r.maxPeriods()>10000) || r.version()<0) throw new IllegalArgumentException("定投期数或版本不合法。");
        if(r.frequency()==null || !Set.of("DAILY","WEEKLY","MONTHLY").contains(r.frequency()) || r.dayValue()<1 || r.dayValue()>31 || r.frequency().equals("WEEKLY") && r.dayValue()>7) throw new IllegalArgumentException("定投周期或日期参数不合法。");
        if(r.endDate()!=null && r.endDate().isBefore(r.startDate())) throw new IllegalArgumentException("结束日期不能早于开始日期。");
    }
    private void validateAmount(BigDecimal amount) {
        if(amount.compareTo(new BigDecimal("0.01"))<0 || amount.compareTo(new BigDecimal("100000000"))>0 || amount.stripTrailingZeros().scale()>2)
            throw new IllegalArgumentException("模拟金额须在 0.01 至 100000000 元之间，最多两位小数。");
    }
    private boolean samePlan(Plan p,PlanRequest r) {
        return p.fundCode().equals(r.fundCode()) && p.amount().compareTo(r.amount())==0 && p.frequency().equals(r.frequency()) &&
                p.dayValue()==r.dayValue() && p.startDate().equals(r.startDate()) && Objects.equals(p.endDate(),r.endDate()) && Objects.equals(p.maxPeriods(),r.maxPeriods());
    }
    private String orderHash(OrderRequest r) {
        return repo.hash(List.of(r.fundCode(),r.side(),r.amount()==null ? "" : r.amount().stripTrailingZeros().toPlainString(),
                r.shares()==null ? "" : r.shares().stripTrailingZeros().toPlainString(),r.pausePlan()));
    }
    private Order matching(Order order,String hash) {
        if(!order.requestHash().equals(hash)) throw new SimulationException("SIM_DUPLICATE_KEY","相同请求标识不能提交不同交易。");
        return order;
    }
    private SimulationException conflict() { return new SimulationException("SIM_PLAN_CHANGED","计划已发生变化，请刷新后重新操作。"); }
    private void validCode(String code) { if(code==null || !code.matches("[0-9]{6}")) throw new IllegalArgumentException("基金代码必须是六位数字。"); }
    private void validatePage(int page,int size) { if(page<1 || page>100000 || size<1 || size>100) throw new IllegalArgumentException("分页参数不合法。"); }
}
