package com.fundradar.core.simulation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fundradar.core.common.trace.TraceContext;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import static com.fundradar.core.simulation.SimulationTypes.*;

/** PostgreSQL 模拟账本仓储；所有个人读写显式携带服务端取得的归属键。 */
@Repository
public class SimulationRepository {
    private final JdbcClient db;
    private final JdbcTemplate batch;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    public SimulationRepository(JdbcClient db,JdbcTemplate batch) { this.db=db; this.batch=batch; }

    public String encode(Object value) {
        try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException("模拟记录序列化失败",e); }
    }
    public <T> T decode(String value,Class<T> type) {
        try { return value==null ? null : json.readValue(value,type); } catch (Exception e) { throw new IllegalStateException("模拟记录解析失败",e); }
    }
    public String hash(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode(value).getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    /** 一人一个事务锚点；写入订单、撤单、定投、结算统一串行，防止超卖与重复结算。 */
    public void lock(UUID user) {
        db.sql("INSERT INTO sim_account(user_id) VALUES (:user) ON CONFLICT DO NOTHING").param("user",user).update();
        db.sql("SELECT user_id FROM sim_account WHERE user_id=:user FOR UPDATE").param("user",user).query(UUID.class).single();
    }
    public Order findRequest(UUID user,String key) {
        return db.sql("SELECT * FROM sim_order WHERE user_id=:user AND request_key=:key").param("user",user)
                .param("key",key).query(orderMapper()).optional().orElse(null);
    }
    public record PendingTotals(BigDecimal amount,int count) {}
    public PendingTotals pendingTotals(UUID user) {
        return db.sql("SELECT COALESCE(sum(amount),0),count(*) FROM sim_order WHERE user_id=:user AND status='PENDING'")
                .param("user",user).query((r,n) -> new PendingTotals(r.getBigDecimal(1),r.getInt(2))).single();
    }
    public int pendingBuys(UUID user,String code) {
        return db.sql("SELECT count(*) FROM sim_order WHERE user_id=:user AND fund_code=:code AND status='PENDING' AND side='BUY'")
                .param("user",user).param("code",code).query(Integer.class).single();
    }
    public Order order(UUID user,UUID id) {
        return db.sql("SELECT * FROM sim_order WHERE user_id=:user AND order_id=:id").param("user",user)
                .param("id",id).query(orderMapper()).optional().orElseThrow(() -> new SimulationException("SIM_NOT_FOUND","未找到该模拟订单。"));
    }
    public void insertOrder(Order o) {
        db.sql("""
            INSERT INTO sim_order(order_id,user_id,fund_code,fund_name,side,amount,shares,trade_date,eligible_date,
                status,source_kind,request_key,request_hash,created_at,rule_version)
            VALUES (:id,:user,:code,:name,:side,:amount,:shares,:trade,:eligible,'PENDING',:source,:key,:hash,:now,:rule)
            """).param("id",o.orderId()).param("user",o.userId()).param("code",o.fundCode()).param("name",o.fundName())
                .param("side",o.side()).param("amount",o.amount()).param("shares",o.shares()).param("trade",o.tradeDate())
                .param("eligible",o.eligibleDate()).param("source",o.sourceKind()).param("key",o.requestKey())
                .param("hash",o.requestHash()).param("now",ts(o.createdAt())).param("rule",o.ruleVersion()).update();
        ledger(o.userId(),o.fundCode(),"submit:"+o.orderId(),"ORDER_SUBMITTED",o,o.createdAt());
        db.sql("""
            INSERT INTO sim_position(user_id,fund_code,snapshot,updated_at) VALUES (:user,:code,CAST(:data AS jsonb),:now)
            ON CONFLICT DO NOTHING
            """).param("user",o.userId()).param("code",o.fundCode())
                .param("data",encode(SimulationAccounting.empty(o.fundCode(),o.fundName()).position()))
                .param("now",ts(o.createdAt())).update();
    }
    public void cancel(Order o,Instant now) {
        db.sql("UPDATE sim_order SET status='CANCELLED' WHERE order_id=:id AND user_id=:user AND status='PENDING'")
                .param("id",o.orderId()).param("user",o.userId()).update();
        ledger(o.userId(),o.fundCode(),"cancel:"+o.orderId(),"ORDER_CANCELLED",Map.of("orderId",o.orderId()),now);
        db.sql("""
            DELETE FROM sim_position p WHERE p.user_id=:user AND p.fund_code=:code
            AND NOT EXISTS (SELECT 1 FROM sim_order o WHERE o.user_id=p.user_id AND o.fund_code=p.fund_code AND o.status<>'CANCELLED')
            """).param("user",o.userId()).param("code",o.fundCode()).update();
    }
    public List<Order> orders(UUID user,String code) {
        var result=db.sql("""
            SELECT * FROM sim_order WHERE user_id=:user AND fund_code=:code
            ORDER BY trade_date,created_at,order_id LIMIT 20001
            """).param("user",user).param("code",code).query(orderMapper()).list();
        if (result.size()>20000) throw new SimulationException("SIM_HISTORY_LIMIT","单基金交易历史超过当前重算容量，需分段核算。");
        return result;
    }
    public Page<Order> orderPage(UUID user,int page,int size,String code) {
        String filter=code==null ? "" : " AND fund_code=:code";
        var query=db.sql("SELECT * FROM sim_order WHERE user_id=:user"+filter+" ORDER BY created_at DESC,order_id DESC LIMIT :size OFFSET :offset")
                .param("user",user).param("size",size).param("offset",(page-1)*size);
        var count=db.sql("SELECT count(*) FROM sim_order WHERE user_id=:user"+filter).param("user",user);
        if (code!=null) { query.param("code",code); count.param("code",code); }
        return new Page<>(query.query(orderMapper()).list(),page,size,count.query(Long.class).single());
    }
    public List<Position> positions(UUID user) {
        var frozen=new HashMap<String,BigDecimal>();
        db.sql("SELECT fund_code,sum(shares) AS frozen FROM sim_order WHERE user_id=:user AND side='SELL' AND status='PENDING' GROUP BY fund_code")
                .param("user",user).query((r,n) -> { frozen.put(r.getString(1),r.getBigDecimal(2)); return 0; }).list();
        return db.sql("SELECT snapshot,issue FROM sim_position WHERE user_id=:user ORDER BY fund_code").param("user",user)
                .query((r,n) -> withState(decode(r.getString(1),Position.class),frozen,r.getString(2))).list();
    }
    private Position withState(Position p,Map<String,BigDecimal> frozen,String issue) {
        BigDecimal f=frozen.getOrDefault(p.fundCode(),SimulationAccounting.ZERO);
        // 结算快照的可用份额已排除未到可用日的批次；此处再扣掉快照之后新下的待确认卖出冻结。
        return new Position(p.fundCode(),p.fundName(),p.shares(),f,issue==null ? p.availableShares().subtract(f).max(BigDecimal.ZERO) : BigDecimal.ZERO,
                p.cost(),p.marketValue(),p.holdingGain(),p.holdingGainRate(),p.realizedGain(),p.dividendGain(),p.receivableDividend(),
                p.paidDividend(),p.cumulativeGain(),p.dailyGain(),p.totalBuy(),p.totalSell(),p.unitNav(),p.navDate(),issue);
    }
    /** 加载当前生效的模拟费率规则；只取生效区间覆盖今天的记录，同一基金申购费率取最新生效的一条。 */
    public Map<String,FeeSchedule> loadFees() {
        Map<String,List<FeeBand>> bands=new HashMap<>();
        Map<String,BigDecimal> purchases=new HashMap<>();
        db.sql("""
            SELECT fund_code,fee_type,min_days,max_days,rate FROM sim_fee_rule
            WHERE effective_from<=CURRENT_DATE AND (effective_to IS NULL OR effective_to>=CURRENT_DATE)
            ORDER BY fund_code,fee_type,effective_from DESC
            """).query((r,n) -> {
            String code=r.getString(1);
            if ("PURCHASE".equals(r.getString(2))) {
                purchases.putIfAbsent(code,r.getBigDecimal(5));
            } else {
                bands.computeIfAbsent(code,c -> new ArrayList<>()).add(new FeeBand(r.getInt(3),r.getObject(4,Integer.class),r.getBigDecimal(5)));
            }
            return 0;
        }).list();
        var result=new HashMap<String,FeeSchedule>();
        var codes=new HashSet<>(bands.keySet()); codes.addAll(purchases.keySet());
        for (String code : codes) {
            result.put(code,new FeeSchedule(purchases.getOrDefault(code,SimulationAccounting.ZERO),bands.getOrDefault(code,List.of())));
        }
        return result;
    }
    // ---------- 费率配置管理 ----------
    /** 全量刷新单基金费率：先终止旧生效规则，再写入当日新版本；同日重复抓取按唯一键幂等更新。 */
    public void upsertFees(FundFee fee) {
        db.sql("""
            UPDATE sim_fee_rule SET effective_to=CURRENT_DATE-1, updated_at=now()
            WHERE fund_code=:code AND effective_to IS NULL AND effective_from<CURRENT_DATE
            """).param("code",fee.fundCode()).update();
        upsertFee(fee.fundCode(),fee.fundName(),"PURCHASE",0,null,fee.purchaseRate(),fee.discountInfo(),fee.dataSource());
        for(var band : fee.redeemBands()) {
            upsertFee(fee.fundCode(),fee.fundName(),"REDEEM",band.minDays(),band.maxDays(),band.rate(),null,fee.dataSource());
        }
    }
    /** 申购规则 min_days 固定存 0 作档位占位，保证唯一索引下的同日幂等更新（NULL 不参与唯一约束）。 */
    private void upsertFee(String code,String name,String type,int min,Integer max,BigDecimal rate,String discount,String source) {
        db.sql("""
            INSERT INTO sim_fee_rule(fund_code,fund_name,fee_type,min_days,max_days,rate,discount_info,data_source,effective_from,updated_at)
            VALUES (:code,:name,:type,:min,:max,:rate,:discount,:source,CURRENT_DATE,now())
            ON CONFLICT (fund_code,fee_type,min_days,effective_from) DO UPDATE SET
                max_days=EXCLUDED.max_days, rate=EXCLUDED.rate, discount_info=EXCLUDED.discount_info,
                fund_name=EXCLUDED.fund_name, data_source=EXCLUDED.data_source,
                version=sim_fee_rule.version+1, updated_at=now()
            """).param("code",code).param("name",name).param("type",type).param("min",min)
                .param("max",max,java.sql.Types.INTEGER).param("rate",rate).param("discount",discount,java.sql.Types.VARCHAR)
                .param("source",source).update();
    }
    /** 乐观锁更新费率；版本不匹配时抛冲突异常，防止两个管理员互相覆盖。 */
    public void updateFeeRule(long ruleId,BigDecimal rate,int version) {
        int changed=db.sql("""
            UPDATE sim_fee_rule SET rate=:rate, version=version+1, updated_at=now()
            WHERE rule_id=:id AND version=:version
            """).param("rate",rate).param("id",ruleId).param("version",version).update();
        if(changed==0) throw new SimulationException("SIM_FEE_VERSION_CONFLICT","费率配置已被他人修改，请刷新后重试。");
    }
    public FeeRuleRow findFeeRule(long ruleId) {
        return db.sql("SELECT * FROM sim_fee_rule WHERE rule_id=:id").param("id",ruleId)
                .query(feeRuleMapper()).optional().orElseThrow(() -> new SimulationException("SIM_NOT_FOUND","未找到该费率规则。"));
    }
    /** 费率规则分页：按基金代码过滤时走 ix_sim_fee_rule_fund 索引，总数单独 COUNT。 */
    public Page<FeeRuleRow> pageRules(String fundCode,int page,int pageSize) {
        long total=db.sql("SELECT count(*) FROM sim_fee_rule WHERE (:code IS NULL OR fund_code=:code)")
                .param("code",fundCode,java.sql.Types.VARCHAR).query(Long.class).single();
        var items=db.sql("""
            SELECT * FROM sim_fee_rule WHERE (:code IS NULL OR fund_code=:code)
            ORDER BY fund_code,fee_type,min_days NULLS LAST,effective_from DESC
            LIMIT :limit OFFSET :offset
            """).param("code",fundCode,java.sql.Types.VARCHAR).param("limit",pageSize).param("offset",(page-1)*pageSize)
                .query(feeRuleMapper()).list();
        return new Page<>(items,page,pageSize,total);
    }
    /** 模拟范围内出现过的全部基金代码：持仓与定投计划取并集，作为全量费率初始化的范围。 */
    public List<String> simFundCodes() {
        return db.sql("""
            SELECT fund_code FROM (
                SELECT DISTINCT fund_code FROM sim_position
                UNION SELECT DISTINCT fund_code FROM sim_plan
            ) codes ORDER BY fund_code
            """).query(String.class).list();
    }
    private RowMapper<FeeRuleRow> feeRuleMapper() {
        return (r,n) -> new FeeRuleRow(r.getLong("rule_id"),r.getString("fund_code"),r.getString("fund_name"),
                r.getString("fee_type"),r.getObject("min_days",Integer.class),r.getObject("max_days",Integer.class),
                r.getBigDecimal("rate"),r.getString("discount_info"),r.getString("data_source"),
                r.getObject("effective_from",LocalDate.class),r.getObject("effective_to",LocalDate.class),
                r.getInt("version"),r.getTimestamp("updated_at")==null?null:r.getTimestamp("updated_at").toInstant());
    }
    public void issue(UUID user,String code,String message,Instant now) {
        db.sql("UPDATE sim_position SET issue=:message,updated_at=:now WHERE user_id=:user AND fund_code=:code")
                .param("message",message).param("now",ts(now)).param("user",user).param("code",code).update();
    }
    public void saveCalculation(UUID user,Calculation result,Instant now) {
        var existing=new HashMap<UUID,Order>(); orders(user,result.position().fundCode()).forEach(o -> existing.put(o.orderId(),o));
        for (var execution : result.executions()) {
            Order old=existing.get(execution.orderId());
            if(old==null) throw new IllegalStateException("结算结果包含未知订单。");
            if (old.execution()==null || !hash(old.execution()).equals(hash(execution))) {
                ledger(user,old.fundCode(),"order:"+old.orderId(),old.execution()==null ? old.side()+"_CONFIRMED" : "NAV_CORRECTION",execution,now);
                db.sql("""
                    UPDATE sim_order SET status='CONFIRMED',execution=CAST(:data AS jsonb),confirmed_at=COALESCE(confirmed_at,:now)
                    WHERE order_id=:id AND user_id=:user
                    """).param("data",encode(execution)).param("now",ts(now)).param("id",old.orderId()).param("user",user).update();
            }
        }
        for (var dividend : result.dividends()) {
            ledger(user,result.position().fundCode(),"dividend:"+dividend.eventKey(),dividend.paid() ? "DIVIDEND_PAID" : "DIVIDEND_RECEIVABLE",dividend,now);
        }
        db.sql("""
            INSERT INTO sim_position(user_id,fund_code,snapshot,updated_at,issue) VALUES (:user,:code,CAST(:data AS jsonb),:now,NULL)
            ON CONFLICT(user_id,fund_code) DO UPDATE SET snapshot=EXCLUDED.snapshot,updated_at=EXCLUDED.updated_at,issue=NULL
            """).param("user",user).param("code",result.position().fundCode()).param("data",encode(result.position())).param("now",ts(now)).update();
        if(!result.daily().isEmpty()) {
            // 曲线是可重建投影；来源撤回某日净值后不能继续混入旧点，原交易证据仍在追加流水中。
            db.sql("DELETE FROM sim_daily_valuation WHERE user_id=:user AND fund_code=:code AND valuation_date NOT IN (:dates)")
                    .param("user",user).param("code",result.position().fundCode())
                    .param("dates",result.daily().stream().map(Daily::date).toList()).update();
        }
        batch.batchUpdate("""
                INSERT INTO sim_daily_valuation(user_id,fund_code,valuation_date,market_value,cumulative_gain,daily_gain)
                VALUES (?,?,?,?,?,?)
                ON CONFLICT(user_id,fund_code,valuation_date) DO UPDATE SET market_value=EXCLUDED.market_value,
                    cumulative_gain=EXCLUDED.cumulative_gain,daily_gain=EXCLUDED.daily_gain
                WHERE (sim_daily_valuation.market_value,sim_daily_valuation.cumulative_gain,sim_daily_valuation.daily_gain)
                IS DISTINCT FROM (EXCLUDED.market_value,EXCLUDED.cumulative_gain,EXCLUDED.daily_gain)
                """,result.daily(),500,(statement,d) -> {
            statement.setObject(1,user); statement.setString(2,result.position().fundCode()); statement.setObject(3,d.date());
            statement.setBigDecimal(4,d.marketValue()); statement.setBigDecimal(5,d.cumulativeGain()); statement.setBigDecimal(6,d.dailyGain());
        });
    }
    public void ledger(UUID user,String code,String key,String type,Object payload,Instant now) {
        String data=encode(payload);
        var previous=db.sql("""
            SELECT revision,payload=CAST(:data AS jsonb) AS unchanged FROM sim_ledger_entry
            WHERE user_id=:user AND event_key=:key ORDER BY event_sequence DESC LIMIT 1
            """).param("data",data).param("user",user).param("key",key)
                .query((r,n) -> Map.entry(r.getString(1),r.getBoolean(2))).optional().orElse(null);
        if(previous!=null && previous.getValue()) return;
        String revision=hash(List.of(previous==null ? "ROOT" : previous.getKey(),data));
        db.sql("""
            INSERT INTO sim_ledger_entry(entry_id,user_id,fund_code,event_key,entry_type,payload,revision,created_at)
            VALUES (:id,:user,:code,:key,:type,CAST(:data AS jsonb),:hash,:now) ON CONFLICT DO NOTHING
            """).param("id",UUID.randomUUID()).param("user",user).param("code",code).param("key",key).param("type",type)
                .param("data",data).param("hash",revision).param("now",ts(now)).update();
    }
    public List<Daily> performance(UUID user,String code,LocalDate start,LocalDate end) {
        return db.sql("""
            SELECT valuation_date,market_value,cumulative_gain,daily_gain FROM sim_daily_valuation
            WHERE user_id=:user AND fund_code=:code AND valuation_date BETWEEN :start AND :end ORDER BY valuation_date
            """).param("user",user).param("code",code).param("start",start).param("end",end)
                .query((r,n) -> new Daily(r.getObject(1,LocalDate.class),r.getBigDecimal(2),r.getBigDecimal(3),r.getBigDecimal(4))).list();
    }
    public Page<Map<String,Object>> ledgerPage(UUID user,int page,int size,String code) {
        String filter=code==null ? "" : " AND fund_code=:code";
        var q=db.sql("SELECT entry_id,fund_code,entry_type,payload,created_at FROM sim_ledger_entry WHERE user_id=:user"+filter+
                " ORDER BY event_sequence DESC LIMIT :size OFFSET :offset").param("user",user).param("size",size).param("offset",(page-1)*size);
        var count=db.sql("SELECT count(*) FROM sim_ledger_entry WHERE user_id=:user"+filter).param("user",user);
        if (code!=null) { q.param("code",code); count.param("code",code); }
        return new Page<>(q.query((r,n) -> Map.<String,Object>of("entryId",r.getObject(1),"fundCode",r.getString(2),
                "entryType",r.getString(3),"payload",decode(r.getString(4),Map.class),"createdAt",r.getTimestamp(5).toInstant())).list(),
                page,size,count.query(Long.class).single());
    }
    public List<Plan> plans(UUID user) {
        return db.sql(planSelect()+" WHERE p.user_id=:user ORDER BY p.created_at DESC LIMIT 501").param("user",user).query(planMapper()).list();
    }
    public Plan plan(UUID user,UUID id) {
        return db.sql(planSelect()+" WHERE p.user_id=:user AND p.plan_id=:id").param("user",user).param("id",id).query(planMapper())
                .optional().orElseThrow(() -> new SimulationException("SIM_NOT_FOUND","未找到该定投计划。"));
    }
    private String planSelect() {
        return """
            SELECT p.*, (SELECT count(*) FROM sim_plan_execution e WHERE e.plan_id=p.plan_id AND e.status='ORDERED') AS ordered_periods,
                (SELECT COALESCE(sum(o.amount),0) FROM sim_plan_execution e JOIN sim_order o ON o.order_id=e.order_id
                 WHERE e.plan_id=p.plan_id AND o.status='CONFIRMED') AS invested_amount FROM sim_plan p
            """;
    }
    public void savePlan(Plan p) {
        db.sql("""
            INSERT INTO sim_plan(plan_id,user_id,fund_code,fund_name,amount,frequency,day_value,start_date,end_date,max_periods,
                scheduled_date,execution_date,status,version,request_key,created_at)
            VALUES (:id,:user,:code,:name,:amount,:frequency,:day,:start,:end,:max,:scheduled,:execution,:status,:version,:key,:now)
            ON CONFLICT(plan_id) DO UPDATE SET amount=EXCLUDED.amount,frequency=EXCLUDED.frequency,day_value=EXCLUDED.day_value,
                start_date=EXCLUDED.start_date,end_date=EXCLUDED.end_date,max_periods=EXCLUDED.max_periods,
                scheduled_date=EXCLUDED.scheduled_date,execution_date=EXCLUDED.execution_date,status=EXCLUDED.status,version=EXCLUDED.version
            """).param("id",p.planId()).param("user",p.userId()).param("code",p.fundCode()).param("name",p.fundName())
                .param("amount",p.amount()).param("frequency",p.frequency()).param("day",p.dayValue()).param("start",p.startDate())
                .param("end",p.endDate()).param("max",p.maxPeriods()).param("scheduled",p.scheduledDate()).param("execution",p.executionDate())
                .param("status",p.status()).param("version",p.version()).param("key",p.requestKey()).param("now",ts(p.createdAt())).update();
    }
    public void period(Plan plan,UUID order,String status,String message,Instant now) {
        periodAt(plan,plan.scheduledDate(),plan.executionDate(),order,status,message,now);
    }
    /** 手动补录的期次按真实买入日落账，不占用计划自身排期日期；同日已有排期记录时不重复写入。 */
    public void periodAt(Plan plan,LocalDate scheduled,LocalDate execution,UUID order,String status,String message,Instant now) {
        db.sql("""
            INSERT INTO sim_plan_execution(execution_id,plan_id,scheduled_date,execution_date,plan_version,status,order_id,message,created_at)
            VALUES (:id,:plan,:scheduled,:execution,:version,:status,:order,:message,:now) ON CONFLICT DO NOTHING
            """).param("id",UUID.randomUUID()).param("plan",plan.planId()).param("scheduled",scheduled)
                .param("execution",execution).param("version",plan.version()).param("status",status).param("order",order)
                .param("message",message).param("now",ts(now)).update();
    }
    public Page<Period> periods(UUID user,UUID plan,int page,int size) {
        plan(user,plan);
        var items=db.sql("SELECT * FROM sim_plan_execution WHERE plan_id=:plan ORDER BY scheduled_date DESC LIMIT :size OFFSET :offset")
                .param("plan",plan).param("size",size).param("offset",(page-1)*size).query((r,n) -> new Period(r.getObject("execution_id",UUID.class),
                        plan,r.getObject("scheduled_date",LocalDate.class),r.getObject("execution_date",LocalDate.class),r.getString("status"),
                        r.getObject("order_id",UUID.class),r.getString("message"),r.getTimestamp("created_at").toInstant())).list();
        return new Page<>(items,page,size,db.sql("SELECT count(*) FROM sim_plan_execution WHERE plan_id=:plan").param("plan",plan).query(Long.class).single());
    }
    public List<UUID> workerUsers(UUID after,int size) {
        return db.sql("""
            SELECT a.user_id FROM sim_account a JOIN user_account u ON u.user_id=a.user_id
            WHERE u.status='ACTIVE' AND (:after IS NULL OR a.user_id > CAST(:after AS uuid)) ORDER BY a.user_id LIMIT :size
            """).param("after",after==null ? null : after.toString(),java.sql.Types.VARCHAR).param("size",size).query(UUID.class).list();
    }
    public Map<String,LocalDate> marketNeeds() {
        var result=new LinkedHashMap<String,LocalDate>();
        db.sql("""
            SELECT fund_code,min(first_date) FROM (
                SELECT fund_code,min(trade_date) AS first_date FROM sim_order WHERE status<>'CANCELLED' GROUP BY fund_code
                UNION ALL SELECT fund_code,min(start_date) FROM sim_plan WHERE status IN ('ACTIVE','PAUSED') GROUP BY fund_code
            ) x GROUP BY fund_code ORDER BY fund_code
            """).query((r,n) -> { result.put(r.getString(1),r.getObject(2,LocalDate.class)); return 0; }).list();
        return result;
    }
    public JobState job(String name) {
        return db.sql("SELECT * FROM sim_job_state WHERE job_name=:name").param("name",name).query((r,n) -> new JobState(r.getString("status"),
                instant(r,"attempted_at"),instant(r,"completed_at"),r.getString("message"))).optional().orElse(null);
    }
    public void job(String name,String status,String message,Instant now,boolean done) {
        db.sql("""
            INSERT INTO sim_job_state(job_name,status,message,attempted_at,completed_at) VALUES (:name,:status,:message,:now,:done)
            ON CONFLICT(job_name) DO UPDATE SET status=:status,message=:message,
                attempted_at=CASE WHEN :finished THEN sim_job_state.attempted_at ELSE :now END,completed_at=:done
            """).param("name",name).param("status",status).param("message",message).param("now",ts(now))
                .param("done",done ? ts(now) : null,java.sql.Types.TIMESTAMP).param("finished",done).update();
    }
    public void audit(UUID user,String action,String target) {
        db.sql("INSERT INTO audit_log(audit_log_id,trace_id,actor,action,target_id) VALUES (:id,:trace,:actor,:action,:target)")
                .param("id",UUID.randomUUID()).param("trace",TraceContext.getTraceId()).param("actor",user.toString())
                .param("action",action).param("target",target).update();
    }
    private RowMapper<Order> orderMapper() {
        return (r,n) -> new Order(r.getObject("order_id",UUID.class),r.getObject("user_id",UUID.class),r.getString("fund_code"),r.getString("fund_name"),
                r.getString("side"),r.getBigDecimal("amount"),r.getBigDecimal("shares"),r.getObject("trade_date",LocalDate.class),r.getObject("eligible_date",LocalDate.class),
                r.getString("status"),r.getString("source_kind"),r.getString("request_key"),r.getString("request_hash"),instant(r,"created_at"),
                instant(r,"confirmed_at"),decode(r.getString("execution"),Execution.class),r.getString("rule_version"));
    }
    private RowMapper<Plan> planMapper() {
        return (r,n) -> new Plan(r.getObject("plan_id",UUID.class),r.getObject("user_id",UUID.class),r.getString("fund_code"),r.getString("fund_name"),
                r.getBigDecimal("amount"),r.getString("frequency"),r.getInt("day_value"),r.getObject("start_date",LocalDate.class),r.getObject("end_date",LocalDate.class),
                r.getObject("max_periods",Integer.class),r.getObject("scheduled_date",LocalDate.class),r.getObject("execution_date",LocalDate.class),
                r.getString("status"),r.getInt("version"),r.getObject("request_key",UUID.class),instant(r,"created_at"),r.getLong("ordered_periods"),r.getBigDecimal("invested_amount"));
    }
    private static Instant instant(ResultSet r,String field) throws SQLException { Timestamp t=r.getTimestamp(field); return t==null ? null : t.toInstant(); }
    private static Timestamp ts(Instant value) { return value==null ? null : Timestamp.from(value); }
}
