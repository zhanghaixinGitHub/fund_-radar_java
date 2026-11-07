package com.fundradar.core.simulation;

import com.fundradar.core.auth.*;
import com.fundradar.core.auth.service.AccessDeniedException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.fundradar.core.simulation.SimulationTypes.*;
import static com.fundradar.core.simulation.SimulationService.*;
import static com.fundradar.core.simulation.SimulationAccountingTests.*;

/** 真实 PostgreSQL 事务内创建隔离测试用户，所有模拟账务在用例结束后回滚。 */
@SpringBootTest(properties="simulation.enabled=false")
@Transactional
class SimulationIntegrationTests {
    @Autowired SimulationService service;
    @Autowired SimulationFeeService fees;
    @Autowired SimulationRepository repo;
    @Autowired com.fundradar.core.portfolio.importer.ConfirmedPortfolioResetService screenshotReset;
    @Autowired JdbcClient db;
    @Autowired org.springframework.web.context.WebApplicationContext web;
    @MockitoBean SimulationMarketClient client;
    @MockitoBean Clock clock;
    UUID user;
    Instant now;
    Market current;
    @BeforeEach void setup() {
        now=at("2026-09-07T09:00:00");
        when(clock.instant()).thenAnswer(i -> now);
        when(client.calendar()).thenReturn(data());
        current=market(List.of(nav("2026-09-07","1"),nav("2026-09-08","1.1"),nav("2026-09-09","1.1"),nav("2026-09-10","1.1")),List.of());
        when(client.market(anyString(),any(),any())).thenAnswer(i -> current);
        user=createUser(); login(user);
    }
    @AfterEach void clear() { CurrentUserContext.clear(); }
    UUID createUser() {
        UUID id=UUID.randomUUID();
        db.sql("INSERT INTO user_account(user_id,mobile,display_name,password_hash,role,status) VALUES (:id,:mobile,'模拟集成测试','test-only-hash','FUND_USER','ACTIVE')")
                .param("id",id).param("mobile","139"+String.format("%08d",ThreadLocalRandom.current().nextInt(100000000))).update();
        return id;
    }
    void login(UUID id) { CurrentUserContext.set(new AuthenticatedUser(id,"test","模拟集成测试",AccountRole.FUND_USER,
            Set.of(PermissionCode.PORTFOLIO_SELF_READ,PermissionCode.SIM_PORTFOLIO_SELF_WRITE,PermissionCode.SIM_PLAN_SELF_WRITE))); }
    OrderRequest buy(String amount) { return new OrderRequest(UUID.randomUUID(),"000001","BUY",num(amount),null,false); }
    OrderRequest sell(String shares) { return new OrderRequest(UUID.randomUUID(),"000001","SELL",null,num(shares),false); }
    void settle(String time) { now=at(time); service.processUser(user,calendar(),Map.of("000001",current),now); }
    PlanRequest daily(Integer max) { return new PlanRequest(UUID.randomUUID(),"000001",num("100"),"DAILY",1,date("2026-09-07"),null,max,0); }

    /** QDII 放开后可预览、买卖和建定投；延迟公布净值仍等待原交易日，不能提前确认或换价。 */
    @Test void qdiiBuySellAndPlanUseUnifiedRulesAndWaitForPublishedNav() {
        String code="018853",name="博时标普石油天然气指数(QDII)-C-CNY";
        current=new Market(code,name,true,null,"TUSHARE_PRO_FUND",List.of(
                new Nav(date("2026-09-07"),num("1.1914"),num("1.1914"),date("2026-09-09"),"qdii-buy"),
                new Nav(date("2026-09-09"),num("1.21"),num("1.21"),date("2026-09-10"),"qdii-sell")),
                List.of(),at("2026-09-10T08:00:00"),"SUCCEEDED",null);
        assertTrue(service.preview(code).supported());
        var buy=service.place(new OrderRequest(UUID.randomUUID(),code,"BUY",num("334"),null,false));
        var plan=service.savePlan(null,new PlanRequest(UUID.randomUUID(),code,num("10"),"DAILY",1,
                date("2026-09-10"),null,null,0));
        assertEquals("ACTIVE",plan.status());
        now=at("2026-09-08T09:00:00");
        service.processUser(user,calendar(),Map.of(code,current),now);
        assertEquals("PENDING",repo.order(user,buy.orderId()).status());
        now=at("2026-09-09T09:00:00");
        service.processUser(user,calendar(),Map.of(code,current),now);
        var bought=repo.order(user,buy.orderId());
        assertEquals("CONFIRMED",bought.status());
        equal("1.1914",bought.execution().unitNav());
        var sell=service.place(new OrderRequest(UUID.randomUUID(),code,"SELL",null,bought.execution().shares(),false));
        equal("0",service.preview(code).availableShares());
        now=at("2026-09-10T09:00:00");
        service.processUser(user,calendar(),Map.of(code,current),now);
        assertEquals("CONFIRMED",repo.order(user,sell.orderId()).status());
        equal("1.21",repo.order(user,sell.orderId()).execution().unitNav());
        equal("0",service.overview().positions().get(0).shares());
        assertTrue(service.overview().positions().get(0).realizedGain().signum()>0);
    }

    /** 最新本人截图与本人关注共同限定补列范围；有账本、旧截图和其他用户的基金都不能混入。 */
    @Test void confirmedFundListIsIsolatedAndNeverCreatesSimulatedBalances() {
        UUID other=createUser();
        UUID old=confirmedSnapshot(user,"2026-09-01T00:00:00Z");
        UUID latest=confirmedSnapshot(user,"2026-09-02T00:00:00Z");
        UUID foreign=confirmedSnapshot(other,"2026-09-03T00:00:00Z");
        confirmedFund(old,"000002"); confirmedFund(latest,"000001"); confirmedFund(latest,"000003");
        confirmedFund(latest,"000004"); confirmedFund(foreign,"000005");
        for (String code : List.of("000001","000002","000003","000005")) {
            db.sql("INSERT INTO watchlist_item(watchlist_item_id,user_id,fund_code) VALUES (:id,:user,:code)")
                    .param("id",UUID.randomUUID()).param("user",user).param("code",code).update();
        }
        service.place(buy("100"));
        var before=service.overview();
        assertEquals(List.of(new HoldingReference("000003","确认基金000003")),before.unrecordedHoldings());
        equal("0",before.marketValue()); equal("100",before.pendingBuyAmount());
        assertEquals(1,before.positions().size());
        assertEquals(1,service.orders(1,20,null).totalCount());
        db.sql("DELETE FROM watchlist_item WHERE user_id=:user AND fund_code='000003'").param("user",user).update();
        assertTrue(service.overview().unrecordedHoldings().isEmpty());
        login(other);
        assertTrue(service.overview().unrecordedHoldings().isEmpty());
        assertTrue(service.overview().positions().isEmpty());
    }
    UUID confirmedSnapshot(UUID owner,String importedAt) {
        UUID id=UUID.randomUUID();
        db.sql("""
            INSERT INTO portfolio_snapshot(snapshot_id,user_id,source_kind,data_as_of_status,source_description,source_content_hash,imported_at)
            VALUES (:id,:owner,'USER_CONFIRMED_SCREENSHOT','UNKNOWN','事务内确认名单测试',:hash,CAST(:at AS timestamptz))
            """).param("id",id).param("owner",owner).param("hash",id.toString()).param("at",importedAt).update();
        return id;
    }
    void confirmedFund(UUID snapshot,String code) {
        db.sql("""
            INSERT INTO portfolio_holding_snapshot(holding_snapshot_id,snapshot_id,fund_code,fund_name,reported_amount,
              reported_weight_pct,reported_daily_gain_amount,reported_holding_gain_amount,reported_holding_gain_pct,reported_cumulative_gain_amount)
            VALUES (:id,:snapshot,:code,:name,123,1,0,0,0,0)
            """).param("id",UUID.randomUUID()).param("snapshot",snapshot).param("code",code).param("name","确认基金"+code).update();
    }

    UUID openingSource() {
        repo.lock(user); UUID snapshot=confirmedSnapshot(user,"2026-09-07T00:00:00Z"); confirmedFund(snapshot,"000001");
        db.sql("""
            UPDATE portfolio_holding_snapshot SET reported_amount=123.45,reported_holding_gain_amount=-5,
                reported_cumulative_gain_amount=-2 WHERE snapshot_id=:id
            """).param("id",snapshot).update();
        db.sql("UPDATE sim_account SET baseline_snapshot_id=:id WHERE user_id=:u").param("id",snapshot).param("u",user).update();
        return snapshot;
    }
    /** 明确采用后只有一份活动余额；原确认值、定投与其他用户保持隔离，不生成虚构申购订单。 */
    @Test void adoptOpeningBalancesIsIdempotentAndReplaysBuySellInOneLedger() {
        UUID other=createUser(); login(other); service.place(buy("20")); login(user);
        UUID snapshot=openingSource();
        assertEquals(1,service.adoptConfirmedHoldings()); assertEquals(0,service.adoptConfirmedHoldings());
        var overview=service.overview(); var p=overview.positions().get(0);
        assertNull(overview.confirmedBaseline()); equal("123.45",overview.marketValue()); equal("-5",overview.holdingGain());
        equal("-2",overview.cumulativeGain()); assertTrue(p.estimatedShares());
        assertEquals(0,service.orders(1,20,null).totalCount());
        assertEquals(snapshot,repo.openingBalances(user).get("000001").snapshotId());
        assertEquals(1,service.earningsFunds("",1,20).totalCount());
        assertNull(overview.yesterdayIncome().funds().get(0).dailyGain());
        service.place(buy("10")); settle("2026-09-08T09:00:00");
        equal("133.45",service.preview("000001").availableShares());
        service.place(sell("50")); settle("2026-09-08T21:00:00");
        equal("83.45",service.overview().positions().get(0).shares());
        assertNull(service.overview().confirmedBaseline());
        assertEquals(1,repo.openingBalances(user).size());
        now=at("2026-09-09T08:00:00");
        equal("13.345",service.overview().yesterdayIncome().funds().get(0).dailyGain());
        login(other); assertEquals(1,service.orders(1,20,null).totalCount()); assertTrue(repo.openingBalances(other).isEmpty());
    }
    @Test void cancellingAnOrderNeverDeletesTheOpeningBalanceAndPreflightFailureWritesNothing() {
        openingSource(); current=market(List.of(),List.of());
        assertThrows(SimulationException.class,() -> service.adoptConfirmedHoldings());
        assertTrue(repo.openingBalances(user).isEmpty()); assertNotNull(service.overview().confirmedBaseline());
        current=market(List.of(nav("2026-09-07","1")),List.of()); service.adoptConfirmedHoldings();
        var buy=service.place(buy("10")); service.cancel(buy.orderId());
        assertEquals(1,repo.positions(user).size()); equal("123.45",service.overview().marketValue());
        assertTrue(repo.marketNeeds().containsKey("000001"));
    }
    @Test void yesterdayUsesExactBeijingDateAndNeverLatestOrOpeningDayIncome() {
        service.place(buy("1000")); settle("2026-09-08T21:00:00");
        var sameEvening=service.overview().yesterdayIncome();
        assertEquals(date("2026-09-07"),sameEvening.date()); equal("0",sameEvening.funds().get(0).dailyGain());
        now=at("2026-09-09T00:00:00"); equal("100",service.overview().yesterdayIncome().funds().get(0).dailyGain());
        now=at("2026-09-14T08:00:00"); var weekend=service.overview().yesterdayIncome();
        assertEquals("NON_TRADING",weekend.funds().get(0).status()); equal("0",weekend.funds().get(0).dailyGain());
        now=at("2026-09-15T08:00:00"); assertNull(service.overview().yesterdayIncome().funds().get(0).dailyGain());
    }

    /** 删除本人旧账，截图汇总不生成虚构订单；重复导入不能再次清掉后续交易，其他用户保持隔离。 */
    @Test void screenshotResetReplacesHistoryAndPreservesReportedTotalsWithoutInventingTrades() {
        UUID other=createUser(); login(other); service.place(buy("20")); login(user);
        var old=service.place(buy("100")); settle("2026-09-08T09:00:00");
        for(String code:List.of("000001","000002")) db.sql("INSERT INTO watchlist_item(watchlist_item_id,user_id,fund_code) VALUES(:id,:u,:c)")
                .param("id",UUID.randomUUID()).param("u",user).param("c",code).update();
        var input=screenshotInput();
        var reset=screenshotReset.reset(input);
        assertFalse(reset.alreadyApplied()); assertEquals(2,reset.holdings()); assertEquals(1,reset.plans());
        assertTrue(service.overview().positions().isEmpty());
        assertEquals(0,service.orders(1,20,null).totalCount()); assertEquals(0,service.ledger(1,20,null).totalCount());
        assertTrue(repo.performance(user,"000001",date("2026-09-01"),date("2026-09-10")).isEmpty());
        var overview=service.overview();
        equal("133.45",overview.marketValue()); equal("-4",overview.holdingGain()); equal("0",overview.cumulativeGain());
        assertFalse(overview.complete()); assertNull(overview.confirmedBaseline().dataAsOfDate());
        assertEquals(48,repo.plans(user).get(0).orderedPeriods()); equal("510",repo.plans(user).get(0).investedAmount());
        assertThrows(SimulationException.class,()->repo.order(user,old.orderId()));
        service.place(buy("10"));
        assertTrue(screenshotReset.reset(input).alreadyApplied());
        assertEquals(1,service.orders(1,20,null).totalCount());
        settle("2026-09-09T11:00:00");
        equal("133.45",service.overview().marketValue().subtract(service.overview().positions().stream().map(Position::marketValue).reduce(num("0"),java.math.BigDecimal::add)));
        assertEquals(49,repo.plans(user).get(0).orderedPeriods());
        login(other); assertEquals(1,service.orders(1,20,null).totalCount()); assertNull(service.overview().confirmedBaseline());
    }
    /** 名单不匹配时拒绝清空，用户现有订单和截图指针保持原状。 */
    @Test void screenshotResetRejectsUnconfirmedScopeBeforeDeletingHistory() {
        service.place(buy("100"));
        assertThrows(IllegalArgumentException.class,()->screenshotReset.reset(screenshotInput()));
        assertEquals(1,service.orders(1,20,null).totalCount()); assertNull(service.overview().confirmedBaseline());
    }
    com.fundradar.core.portfolio.importer.ConfirmedPortfolioResetService.Input screenshotInput() {
        var holdings=List.of(new com.fundradar.core.portfolio.importer.LocalPortfolioSnapshotInput.LocalPortfolioHoldingInput(
                "000001","截图基金一",num("123.45"),num("80"),num("0"),num("-5"),num("-4.28"),num("-2")),
                new com.fundradar.core.portfolio.importer.LocalPortfolioSnapshotInput.LocalPortfolioHoldingInput(
                "000002","截图基金二",num("10"),num("5"),num("0"),num("1"),num("10"),num("2")));
        return new com.fundradar.core.portfolio.importer.ConfirmedPortfolioResetService.Input(
                new com.fundradar.core.portfolio.importer.LocalPortfolioSnapshotInput("USER_CONFIRMED_SCREENSHOT","UNKNOWN",null,"事务内用户确认截图",holdings),
                List.of(new com.fundradar.core.portfolio.importer.ConfirmedPortfolioResetService.ReportedPlan("000001",num("10"),48,num("510"),date("2026-09-09"))));
    }

    @Test void buyIsIdempotentAndPendingSharesCannotBeSold() {
        var request=buy("1000"); var first=service.place(request);
        assertEquals(first.orderId(),service.place(request).orderId());
        assertEquals(1,service.orders(1,20,null).totalCount());
        assertThrows(SimulationException.class,() -> service.place(sell("1")));
        assertThrows(SimulationException.class,() -> service.place(new OrderRequest(request.requestKey(),"000001","BUY",num("2000"),null,false)));
        equal("1000",service.overview().pendingBuyAmount());
        assertNull(service.overview().dailyGain());
        assertEquals(0,db.sql("SELECT count(*) FROM watchlist_item WHERE user_id=:user").param("user",user).query(Integer.class).single());
        settle("2026-09-08T09:00:00");
        equal("1000",service.overview().positions().get(0).shares());
        equal("100",service.overview().positions().get(0).dailyGain());
        equal("100",service.overview().dailyGain());
        long count=service.ledger(1,100,null).totalCount(); settle("2026-09-08T09:00:00");
        assertEquals(count,service.ledger(1,100,null).totalCount());
        assertEquals("CONFIRMED",repo.order(user,first.orderId()).status());
    }
    @Test void sellFreezesImmediatelyCancelReleasesAndFullExitKeepsProfit() {
        service.place(buy("1000")); settle("2026-09-08T09:00:00");
        var partial=service.place(sell("600"));
        equal("400",service.preview("000001").availableShares());
        assertThrows(SimulationException.class,() -> service.place(sell("401")));
        service.cancel(partial.orderId()); equal("1000",service.preview("000001").availableShares());
        var all=service.place(sell("1000")); settle("2026-09-09T09:00:00");
        var p=service.overview().positions().get(0);
        equal("0",p.shares()); equal("0",p.cost()); equal("100",p.cumulativeGain()); equal("100",p.realizedGain());
        assertThrows(SimulationException.class,() -> service.cancel(all.orderId()));
    }
    @Test void onlyCancelledInitialBuyDoesNotLeaveGhostHolding() {
        var o=service.place(buy("100")); service.cancel(o.orderId());
        assertTrue(service.overview().positions().isEmpty()); assertEquals(1,service.orders(1,20,null).totalCount());
    }
    @Test void ownerAndWritePermissionsAreEnforcedAtServiceBoundary() {
        var o=service.place(buy("1000")); var plan=service.savePlan(null,daily(null));
        UUID other=createUser(); login(other);
        assertTrue(service.overview().positions().isEmpty()); assertTrue(service.plans().isEmpty());
        assertThrows(SimulationException.class,() -> service.cancel(o.orderId()));
        assertThrows(SimulationException.class,() -> service.planAction(plan.planId(),new PlanAction("END",plan.version())));
        assertThrows(SimulationException.class,() -> service.periods(plan.planId(),1,20));
        CurrentUserContext.set(new AuthenticatedUser(user,"test","test",AccountRole.FUND_USER,Set.of(PermissionCode.PORTFOLIO_SELF_READ)));
        assertThrows(AccessDeniedException.class,() -> service.place(buy("100")));
        assertThrows(AccessDeniedException.class,() -> service.savePlan(null,daily(null)));
    }
    @Test void repeatedPlanRunsGenerateOneOrderAndStopAtMaximum() {
        var request=daily(2); var p=service.savePlan(null,request);
        assertEquals(p.planId(),service.savePlan(null,request).planId());
        settle("2026-09-07T09:59:59"); assertEquals(0,service.orders(1,20,null).totalCount());
        settle("2026-09-07T10:00:00"); settle("2026-09-07T10:01:00");
        assertEquals(1,service.orders(1,20,null).totalCount());
        settle("2026-09-08T10:00:00"); settle("2026-09-08T10:01:00");
        assertEquals(2,service.orders(1,20,null).totalCount());
        assertEquals("ENDED",service.plans().get(0).status()); assertEquals(2,service.periods(p.planId(),1,20).totalCount());
        settle("2026-09-09T10:00:00"); equal("200",service.plans().get(0).investedAmount());
    }
    @Test void manualCatchUpBuysAtPreviousTradingDayNavWithoutShiftingSchedule() {
        var p=service.savePlan(null,daily(null));
        assertEquals(date("2026-09-07"),p.executionDate());
        current=market(List.of(nav("2026-09-04","0.8"),nav("2026-09-07","1"),nav("2026-09-08","1.1")),List.of());
        now=at("2026-09-07T09:30:00");
        var stats=service.catchUpUser(user,calendar(),Map.of("000001",current),now);
        assertEquals(1,stats.plansChecked()); assertEquals(1,stats.ordersCreated()); assertEquals(0,stats.plansSkipped());
        var order=service.orders(1,20,null).items().get(0);
        assertEquals(date("2026-09-04"),order.tradeDate());
        assertEquals("CONFIRMED",repo.order(user,order.orderId()).status());
        equal("125",service.overview().positions().get(0).shares());
        var plan=service.plans().get(0);
        assertEquals(date("2026-09-07"),plan.executionDate()); assertEquals(1,plan.orderedPeriods()); equal("100",plan.investedAmount());
        var again=service.catchUpUser(user,calendar(),Map.of("000001",current),now);
        assertEquals(0,again.ordersCreated()); assertEquals(1,again.plansSkipped());
        assertEquals(1,service.orders(1,20,null).totalCount());
        settle("2026-09-07T10:00:00");
        assertEquals(2,service.orders(1,20,null).totalCount());
    }
    @Test void missedPlanPeriodIsNotBackfilledAtKnownHistoricalNav() {
        var p=service.savePlan(null,daily(null));
        settle("2026-09-08T11:00:00");
        var periods=service.periods(p.planId(),1,20).items();
        assertEquals(List.of("ORDERED","MISSED"),periods.stream().map(SimulationTypes.Period::status).toList());
        assertEquals(1,service.orders(1,20,null).totalCount());
        assertEquals(date("2026-09-08"),service.orders(1,20,null).items().get(0).tradeDate());
    }
    @Test void planEditPauseResumeEndAndSalePauseRetainExistingOrders() {
        var p=service.savePlan(null,daily(null));
        p=service.savePlan(p.planId(),new PlanRequest(UUID.randomUUID(),"000001",num("200"),"DAILY",1,p.startDate(),null,null,p.version()));
        p=service.planAction(p.planId(),new PlanAction("PAUSE",p.version()));
        settle("2026-09-07T10:00:00"); assertEquals(0,service.orders(1,20,null).totalCount());
        p=service.planAction(p.planId(),new PlanAction("RESUME",p.version())); assertEquals(date("2026-09-08"),p.executionDate());
        service.place(buy("1000")); settle("2026-09-08T10:00:00");
        long pendingBuys=repo.pendingBuys(user,"000001");
        service.place(new OrderRequest(UUID.randomUUID(),"000001","SELL",null,num("100"),true));
        p=service.plans().get(0); assertEquals("PAUSED",p.status()); assertEquals(pendingBuys,repo.pendingBuys(user,"000001"));
        assertEquals("ENDED",service.planAction(p.planId(),new PlanAction("END",p.version())).status());
    }
    @Test void sourceCorrectionsAreAppendOnlyEvenWhenSourceReturnsToOriginalValue() {
        var buy=service.place(buy("1000")); settle("2026-09-08T09:00:00");
        Market original=current;
        current=market(List.of(nav("2026-09-07","1.25"),nav("2026-09-08","1.375")),List.of());
        settle("2026-09-08T09:00:00"); equal("800",repo.order(user,buy.orderId()).execution().shares());
        current=original; settle("2026-09-08T09:00:00"); equal("1000",repo.order(user,buy.orderId()).execution().shares());
        assertEquals(3,db.sql("SELECT count(*) FROM sim_ledger_entry WHERE user_id=:user AND event_key=:key")
                .param("user",user).param("key","order:"+buy.orderId()).query(Integer.class).single());
        current=market(List.of(nav("2026-09-08","1.1")),List.of()); settle("2026-09-09T09:00:00");
        var position=service.overview().positions().get(0); equal("1000",position.shares()); assertNotNull(position.issue());
        assertThrows(SimulationException.class,() -> service.place(buy("1")));
    }
    @Test void workerPaginationAndJobWatermarkWorkInPostgres() {
        service.place(buy("1000")); assertTrue(repo.workerUsers(null,50).contains(user));
        assertFalse(repo.workerUsers(user,50).contains(user)); assertTrue(repo.marketNeeds().containsKey("000001"));
        repo.job("test-simulation","RUNNING","测试",now,false); now=now.plusSeconds(10); repo.job("test-simulation","SUCCEEDED","完成",now,true);
        assertEquals(now,repo.job("test-simulation").completedAt());
        assertEquals(now.minusSeconds(10),repo.job("test-simulation").attemptedAt());
    }
    @Test void invalidDecimalPrecisionAndPlanBoundsDoNotReachDatabase() {
        assertThrows(IllegalArgumentException.class,() -> service.place(buy("0.001")));
        assertThrows(IllegalArgumentException.class,() -> service.place(buy("100000001")));
        assertThrows(IllegalArgumentException.class,() -> service.place(sell("0.000000001")));
        assertThrows(IllegalArgumentException.class,() -> service.savePlan(null,daily(0)));
        assertEquals(0,service.orders(1,20,null).totalCount());
    }
    @Test void adminOverviewReadsTargetUserLedgerAndRequiresPortfolioUserRead() {
        service.place(buy("1000")); settle("2026-09-08T09:00:00");
        UUID target=user; var self=service.overview();
        CurrentUserContext.set(new AuthenticatedUser(UUID.randomUUID(),"admin","管理员",AccountRole.SYSTEM_ADMIN,Set.of()));
        var viewed=service.overviewForUser(target);
        equal("1000",viewed.positions().get(0).shares());
        assertEquals(self.marketValue(),viewed.marketValue()); assertEquals(self.rules(),viewed.rules());
        assertTrue(service.overviewForUser(createUser()).positions().isEmpty());
        login(target);
        assertThrows(AccessDeniedException.class,() -> service.overviewForUser(target));
    }
    @Test void httpRequiresSessionCsrfAndSerializesDecimalsAsStrings() throws Exception {
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(web).build();
        var base="/api/v1/sim-portfolios/current";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(base))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/admin/users/"+UUID.randomUUID()+"/sim-portfolio/current"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        String mobile="138"+String.format("%08d",ThreadLocalRandom.current().nextInt(100000000));
        var registration=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/auth/register")
                .contentType("application/json").content("{\"mobile\":\""+mobile+"\",\"password\":\"simulation-test-only\",\"displayName\":\"模拟接口测试\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn();
        var session=registration.getResponse().getCookie("fund_radar_session");
        var csrf=registration.getResponse().getCookie("fund_radar_csrf");
        assertNotNull(session); assertNotNull(csrf);
        String payload="{\"requestKey\":\""+UUID.randomUUID()+"\",\"fundCode\":\"000001\",\"side\":\"BUY\",\"amount\":\"1000.01\",\"pausePlan\":false}";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(base+"/orders")
                        .cookie(session).contentType("application/json").content(payload))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(base+"/orders")
                        .cookie(session,csrf).header("X-CSRF-Token",csrf.getValue()).contentType("application/json").content(payload))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.amount").value("1000.01"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.status").value("PENDING"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(base).cookie(session))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.pendingBuyAmount").value("1000.01"));
    }
    @Test void calendarBoundaryPausesPlanInsteadOfLeavingWorkerFailed() {
        now=at("2026-12-30T09:00:00");
        var p=service.savePlan(null,new PlanRequest(UUID.randomUUID(),"000001",num("100"),"DAILY",1,date("2026-12-30"),null,null,0));
        settle("2026-12-30T10:00:00"); settle("2026-12-31T10:00:00");
        assertEquals("PAUSED",service.plans().get(0).status());
        assertEquals(1,service.periods(p.planId(),1,20).totalCount());
    }
    @Test
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void concurrentSalesCannotOversellTheSameAccount() throws Exception {
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            // 独立提交仅供并发线程共享；禁止另一个正在运行的本地服务处理测试替身行情。
            db.sql("UPDATE user_account SET status='DISABLED' WHERE user_id=:user").param("user",user).update();
            service.place(buy("1000")); settle("2026-09-08T09:00:00");
            var start=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Boolean> attempt=() -> {
                login(user);
                try { start.await(); service.place(sell("600")); return true; }
                catch(SimulationException error) { assertEquals("SIM_INSUFFICIENT_SHARES",error.code()); return false; }
                finally { CurrentUserContext.clear(); }
            };
            var first=executor.submit(attempt); var second=executor.submit(attempt); start.countDown();
            assertNotEquals(first.get(15,java.util.concurrent.TimeUnit.SECONDS),second.get(15,java.util.concurrent.TimeUnit.SECONDS));
            equal("400",service.overview().positions().get(0).availableShares());
        } finally {
            executor.shutdownNow(); executor.awaitTermination(15,java.util.concurrent.TimeUnit.SECONDS);
            // 仅清理本用例刚创建的 UUID 账户，其他用例仍使用自动回滚。
            for(String table : List.of("sim_ledger_entry","sim_daily_valuation","sim_position","sim_order","sim_account")) {
                db.sql("DELETE FROM "+table+" WHERE user_id=:user").param("user",user).update();
            }
            db.sql("DELETE FROM audit_log WHERE actor=:actor").param("actor",user.toString()).update();
            db.sql("DELETE FROM user_account WHERE user_id=:user AND display_name='模拟集成测试'").param("user",user).update();
        }
    }
    @Test void withdrawnNavPointDoesNotRemainInRebuiltPerformanceCurve() {
        service.place(buy("1000")); settle("2026-09-10T09:00:00");
        assertEquals(4,service.performance("000001",date("2026-09-01"),date("2026-09-10")).size());
        current=market(List.of(nav("2026-09-07","1"),nav("2026-09-09","1.1"),nav("2026-09-10","1.1")),List.of());
        settle("2026-09-10T09:01:00");
        var curve=service.performance("000001",date("2026-09-01"),date("2026-09-10"));
        assertEquals(3,curve.size()); assertEquals(date("2026-09-09"),curve.get(1).date()); assertNull(curve.get(1).dailyGain());
    }
    /** 写入一条费率规则；min/max 仅赎回分档使用，申购传 null。 */
    void fee(String type,Integer min,Integer max,String rate) {
        db.sql("""
            INSERT INTO sim_fee_rule(fund_code,fund_name,fee_type,min_days,max_days,rate,data_source,effective_from)
            VALUES ('000001','测试普通混合基金',:type,:min,:max,:rate,'MANUAL','2026-01-01')
            """).param("type",type).param("min",min,java.sql.Types.INTEGER).param("max",max,java.sql.Types.INTEGER)
                .param("rate",num(rate)).update();
    }
    @Test void orderConfirmsSameEveningWhenNavPublishedAndSharesUsableNextDay() {
        var buy=service.place(buy("1000"));             // 9-07 上午下单
        settle("2026-09-07T21:00:00");                  // 当晚净值公布后结算
        assertEquals("CONFIRMED",repo.order(user,buy.orderId()).status());
        var p=service.overview().positions().get(0);
        equal("1000",p.shares()); equal("0",p.availableShares());   // 确认当晚份额未到可用日
        settle("2026-09-08T09:00:00");
        equal("1000",service.overview().positions().get(0).availableShares());  // 次一交易日可卖
    }
    @Test void purchaseFeeAppliesFromFeeRuleTable() {
        fee("PURCHASE",null,null,"0.0008");
        var buy=service.place(buy("100")); settle("2026-09-07T21:00:00");
        var execution=repo.order(user,buy.orderId()).execution();
        equal("0.08",execution.fee()); equal("99.92006395",execution.netAmount());
        equal("99.92006395",execution.shares());
        equal("100",service.overview().positions().get(0).cost());
    }
    @Test void redeemFeeBandsApplyOnSellFromRuleTable() {
        fee("REDEEM",0,6,"0.015"); fee("REDEEM",7,29,"0.005"); fee("REDEEM",30,null,"0");
        var buy=service.place(buy("1000")); settle("2026-09-07T21:00:00");  // 9-07 确认批次
        now=at("2026-09-09T09:00:00");
        var sell=service.place(sell("500")); settle("2026-09-09T21:00:00"); // 持有 2 天 → 1.5%
        var execution=repo.order(user,sell.orderId()).execution();
        equal("550",execution.grossAmount()); equal("8.25",execution.fee()); equal("541.75",execution.netAmount());
        var p=service.overview().positions().get(0);
        equal("500",p.shares()); equal("41.75",p.realizedGain());
    }
    @Test void v1ConfirmedOrderKeepsOriginalExecutionWhenRulesUpgrade() {
        var buy=service.place(buy("1000")); settle("2026-09-07T21:00:00");
        db.sql("UPDATE sim_order SET rule_version='CN_NAV_SIM_V1_NO_FEE' WHERE order_id=:id").param("id",buy.orderId()).update();
        fee("PURCHASE",null,null,"0.0008");
        settle("2026-09-08T09:00:00");  // 费率已配置，但 V1 历史订单重放仍全额换算
        var execution=repo.order(user,buy.orderId()).execution();
        equal("1000",execution.shares()); assertNull(execution.fee());
    }
    @Test void valuationAdvancesEvenWhenPendingOrderNavNotPublished() {
        var buy=service.place(buy("1000")); settle("2026-09-07T21:00:00");
        now=at("2026-09-09T09:00:00"); var stuck=service.place(buy("1100"));
        current=market(List.of(nav("2026-09-07","1"),nav("2026-09-08","1.1"),nav("2026-09-10","1.2")),List.of());
        settle("2026-09-10T09:00:00");  // 9-09 订单净值未公布，但估值不冻结
        assertEquals("PENDING",repo.order(user,stuck.orderId()).status());
        var p=service.overview().positions().get(0);
        assertEquals(date("2026-09-10"),p.navDate()); equal("1000",p.shares()); equal("1200",p.marketValue());
    }
    // ---------- 费率管理 API ----------
    FundFee feeProfile(String purchase,List<FeeBand> bands) {
        return new FundFee("000001","测试普通混合基金",num(purchase),num(purchase),null,bands,"EASTMONEY_F10");
    }
    long insertPurchaseRule(String code,String rate) {
        return db.sql("""
            INSERT INTO sim_fee_rule(fund_code,fund_name,fee_type,min_days,max_days,rate,data_source,effective_from)
            VALUES (:code,'测试普通混合基金','PURCHASE',0,NULL,:rate,'MANUAL','2026-01-01') RETURNING rule_id
            """).param("code",code).param("rate",num(rate)).query(Long.class).single();
    }
    @Test void upsertFeesTerminatesOldRulesAndIsIdempotentWithinDay() {
        fee("PURCHASE",null,null,"0.015");  // 旧申购规则 2026-01-01 生效
        var profile=feeProfile("0.0015",List.of(new FeeBand(0,6,num("0.015")),new FeeBand(7,null,num("0.005"))));
        repo.upsertFees(profile); repo.upsertFees(profile);  // 同日重复抓取幂等，不产生重复规则
        var rows=repo.pageRules("000001",1,50).items();
        var active=rows.stream().filter(r -> r.effectiveTo()==null).toList();
        assertEquals(3,active.size());  // 1 申购 + 2 档赎回
        var purchase=active.stream().filter(r -> r.feeType().equals("PURCHASE")).findFirst().orElseThrow();
        equal("0.0015",purchase.rate()); assertEquals("EASTMONEY_F10",purchase.dataSource());
        assertEquals(1,rows.stream().filter(r -> r.effectiveTo()!=null).count());  // 旧申购规则已终止
    }
    @Test void updateFeeRuleUsesOptimisticLocking() {
        long id=insertPurchaseRule("000001","0.01");
        repo.updateFeeRule(id,num("0.012"),1);  // 成功，版本升为 2
        assertThrows(SimulationException.class,() -> repo.updateFeeRule(id,num("0.013"),1));  // 旧版本冲突
        var row=repo.findFeeRule(id);
        equal("0.012",row.rate()); assertEquals(2,row.version());
    }
    @Test void pageRulesFiltersAndCounts() {
        // 开发库已有公共费率可保留；只核对本事务增加的两条，不依赖全表为空。
        long before=repo.pageRules(null,1,10).totalCount();
        insertPurchaseRule("000001","0.015"); insertPurchaseRule("000002","0.01");
        var page=repo.pageRules("000001",1,10);
        assertEquals(1,page.totalCount()); assertEquals("000001",page.items().get(0).fundCode());
        assertEquals(before+2,repo.pageRules(null,1,10).totalCount());
    }
    @Test void refreshFundFetchesProfileAndPersistsRules() {
        var profile=feeProfile("0.0015",List.of(new FeeBand(0,6,num("0.015")),new FeeBand(7,null,num("0.005"))));
        when(client.fetchFees("000001")).thenReturn(profile);
        fees.refreshFund("000001");
        var active=repo.pageRules("000001",1,50).items().stream().filter(r -> r.effectiveTo()==null).toList();
        assertEquals(3,active.size());
        equal("0.0015",active.stream().filter(r -> r.feeType().equals("PURCHASE")).findFirst().orElseThrow().rate());
    }
    @Test void updateRuleRejectsInvalidRate() {
        long id=insertPurchaseRule("000001","0.01");
        assertThrows(SimulationException.class,() -> fees.updateRule(id,num("1.5"),1));
        assertThrows(SimulationException.class,() -> fees.updateRule(id,num("-0.1"),1));
    }
}
