# 模拟交易：申赎费率与净值公布即结算 — 设计文档

> 目标读者：开发 / 架构评审
> 关联需求：docs_zhx/requirements/sim-fee-and-nav-settlement.md
> 涉及模块：Java 核心服务（com.fundradar.core.simulation）、Python 行情服务（app）、Vue 前端（后台费率维护）

## 1. 现状回顾与根因

### 1.1 根因：blockedDate 截断（SimulationAccounting.calculate）

```
orders 遍历：
  CONFIRMED → applied
  PENDING 且 eligibleDate<=today 且有净值 → applied
  否则 → blockedDate = min(blockedDate, tradeDate)
blockedDate != null 时：applied 去掉 >=blockedDate 的订单；navs.tailMap(blockedDate,true).clear()
```

9-18 上午的定投订单 eligibleDate=9-21（周一），9-18 晚上净值入库后该订单仍"未到确认日"，blockedDate=9-18，**估值被冻结在 9-17**。估值曲线与订单确认耦合是错误点：订单不能确认时，已持有仓位的每日估值也不该停止。

### 1.2 无费率

买入 `quantity = amount ÷ nav` 全额换算（第 94 行）；卖出 `gross = quantity × nav` 全额到账（第 113 行）。真实基金需扣申购费（A 类）与分档赎回费。

### 1.3 同步窗口不足

SimulationScheduler.loadMarkets 第 111 行：refresh 窗口 `hour==7 || hour==20 || hour==22`。22:00 后公布净值的基金要等次日 7:00。

## 2. 目标设计

1. **订单确认与估值解耦**：订单按 tradeDate 顺序连续确认（净值公布即确认，不再等待 eligibleDate）；估值曲线始终推进到最新已公布净值日，与订单确认互不阻塞。
2. **费率模拟**：申购费影响份额；赎回费按 FIFO 批次持有天数分档，从赎回净额中扣减。
3. **费率配置表**：sim_fee_rule 表，天天基金 f10 初始化抓取，后台维护接口。
4. **历史兼容**：重放按每笔订单的 rule_version 决定口径；V1 订单结果不变，9-18 起 PENDING 订单升级 V2 重算。

## 3. 数据模型

### 3.1 新表 sim_fee_rule（Flyway V14）

```sql
CREATE TABLE sim_fee_rule (
    rule_id        BIGSERIAL PRIMARY KEY,
    fund_code      VARCHAR(6)  NOT NULL,
    fund_name      VARCHAR(256) NOT NULL,
    fee_type       VARCHAR(16) NOT NULL CHECK (fee_type IN ('PURCHASE','REDEEM')),
    min_days       INTEGER,                -- 仅 REDEEM：持有自然天数下界（含）；PURCHASE 为 NULL
    max_days       INTEGER,                -- 仅 REDEEM：持有自然天数上界（含）；最后一档 NULL 表示无上限
    rate           NUMERIC(8,4) NOT NULL CHECK (rate >= 0),   -- 0.0008 = 0.08%；0.0150 = 1.5%
    discount_info  VARCHAR(64),            -- 如 '支付宝1折'，展示用
    data_source    VARCHAR(32) NOT NULL,   -- 'EASTMONEY_F10' / 'MANUAL'
    effective_from DATE NOT NULL,
    effective_to   DATE,
    version        INTEGER NOT NULL DEFAULT 1,   -- 乐观锁，后台修改时递增
    created_at     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_sim_fee_rule ON sim_fee_rule(fund_code,fee_type,min_days,effective_from);
CREATE INDEX ix_sim_fee_rule_fund ON sim_fee_rule(fund_code);
```

**读取策略**：结算 tick 每用户结算前，SimulationService 一次性 `loadFeeRules()` 全表加载（当前 43 只基金 × 2 类 × ≤4 档 ≈ 200+ 行，十万用户规模下仍是常量级），进程内做 Map<fundCode, FeeSchedule>。数据量增长到万级基金时升级为「版本号 + Caffeine 缓存 + 变更时主动失效」，设计上预留版本号字段。

**费用命中规则**：无 PURCHASE 行 → 申购费 0（C 类）；无 REDEEM 行 → 赎回费 0；min_days 不匹配任何档 → 按 max_days IS NULL 的最高档。

### 3.2 sim_order 变更

- `rule_version` 默认值升级：`ALTER COLUMN rule_version SET DEFAULT 'CN_NAV_SIM_V2_FEE'`；
- 新订单显式写入 V2；9-18（含）起 PENDING 订单由数据迁移升级 V2（见 §7）；
- execution JSONB 结构扩展（向后兼容，旧数据无新字段）：

```json
{ "shares": "…", "unitNav": "…", "grossAmount": "…", "fee": "…", "netAmount": "…",
  "cost": "…", "realizedGain": "…", "navRevision": "…", "source": "…", "ruleVersion": "CN_NAV_SIM_V2_FEE" }
```

### 3.3 SimulationTypes 契约变更（Java 内部 record）

- `Order` 增加 `ruleVersion`（repo 加载订单时映射）；
- `Execution` 增加 `fee`、`netAmount`、`ruleVersion`；
- `Lot` 内部类增加 `availableOn`（份额可用日 = 订单 eligibleDate）；
- 前端 `SimExecution` 类型增加 `fee`、`netAmount`（可选，兼容旧 execution）。

## 4. 核心链路设计

### 4.1 订单确认规则（SimulationAccounting.calculate 重写应用段）

```java
// 订单按 tradeDate 顺序连续确认；遇到第一个净值未公布的 PENDING 即停止后续确认，
// 但估值曲线不受影响，始终推进到最新已公布净值日。
boolean blocked = false;
for (var order : orders) {
    if (order.status().equals("CONFIRMED")) {
        if (!navs.containsKey(order.tradeDate())) throw invalid("已确认交易的净值缺失，保留上次估值等待核对。");
        applied.add(order);
    } else if (blocked) {
        // 前面的订单尚未确认，按顺序等待
    } else if (navs.containsKey(order.tradeDate())) {
        // 净值已公布（navs 构造时已过滤 announcedOn<=today）：立即按 T 日净值确认，
        // 不再等待 eligibleDate；eligibleDate 仅作为份额可用日。
        applied.add(order);
    } else {
        blocked = true;   // 净值未公布，本单及之后订单等待下一轮结算
    }
}
// 不再有 navs.tailMap(limit,true).clear() —— 估值照常推进
```

**要点**：
- navs 构造时已过滤 `navDate<=today && announcedOn<=today`，因此 `containsKey(tradeDate)` 等价于"净值已公布"；
- `eligibleDate` 语义从"最早确认日"变为"份额可用日"，落库为 Lot.availableOn。

### 4.2 买入（V2 口径，SimulationAccounting 买入分支）

```
净申购金额 netAmount = amount ÷ (1 + purchaseRate)          （8 位 HALF_UP）
份额 quantity        = netAmount ÷ unitNav                  （8 位 DOWN，防止超买）
申购费 fee           = amount − netAmount                    （2 位 HALF_UP）
批次入队 lots.add(new Lot(quantity, amount, availableOn))    （成本记全额投入，收益口径资金流中性）
```

- V1 订单重放仍走原逻辑（amount 全额 ÷ 净值、无费），保证历史结果不变；
- 成本 cost 记全额投入（本金口径），收益 = 市值 − 成本 + 已实现 + 分红，费用自然体现在"份额变少"里，与支付宝口径一致。

### 4.3 卖出（V2 口径）与赎回费分档

```
持有天数 = ChronoUnit.DAYS.between(批次确认日(即批次 tradeDate), 卖出订单 tradeDate)
费率档位 = REDEEM 规则中 min_days<=持有天数 且 (max_days IS NULL 或 持有天数<=max_days)
赎回费 fee = money(gross × rate)
净收入 netAmount = gross − fee
realized = netAmount − 释放成本；sell 累计净收入
```

- FIFO 释放批次时增加防御：批次 `availableOn > today` 直接抛 invalid（"数据修正后可用份额不足"语义兜底，正常路径由下单时 availableShares 检查挡住）；
- V1 卖出订单重放不扣费（保持历史结果）；V2 卖出订单对 V1/V2 批次一律按 V2 分档扣费（费用口径跟随卖出订单，与真实账户一致）。

### 4.4 可用份额（Position.availableShares）

```
availableShares = Σ(availableOn <= today 的批次剩余份额) − 冻结中的 PENDING 卖出份额
```

未可用批次计入总份额与市值（与真实持仓一致），但不可卖出。下单接口的份额校验仍使用 position 快照的 availableShares。

### 4.5 结算调度（SimulationScheduler.loadMarkets）

- refresh 窗口扩展：`hour==7 || hour==20 || hour==22 || hour==0`（0:00~0:59 兜底 22:00 后公布的净值；次日 7:00 继续兜底晚到数据）；
- 其余链路不变：每分钟 tick、advisory lock、每用户锁、重放式结算。

### 4.6 费率初始化抓取与后台维护

**职责划分**：天天基金 f10 抓取放 Python 行情服务（与行情数据源逻辑集中），Java 负责落库与业务规则。

```
Python（app/services/eastmoney_fee.py）
  新路由 GET /internal/v1/funds/{code}/fee （X-Service-Token 认证）
  抓取 https://fundf10.eastmoney.com/jjfl_{code}.html（UA + Referer 头，实测成功）
  解析申购费率（折后）、赎回分档费率、管理/托管/销售服务费率（仅存申赎，其余仅日志参考）
  返回结构化 JSON；抓取失败返回明确错误码

Java（SimulationFeeClient → SimulationFeeService）
  初始化：POST /api/v1/admin/sim-fee-rules/init-all —— 遍历已登记基金批量抓取，
         单基金失败不影响整体，结果统计返回；重复执行幂等（upsert 按唯一键）
  单基金刷新：POST /api/v1/admin/sim-fee-rules/refresh/{fundCode}
  维护：GET 分页列表 / PUT（乐观锁 version）/ 查询条件 fundCode+feeType

前端（workSpace05）
  后台菜单新增"模拟费率维护"页：分页列表、修改费率、单基金重新抓取、全量初始化按钮
```

**抓取结果与人工维护的关系**：人工修改后的规则 data_source='MANUAL'，单基金"重新抓取"会覆盖（提示确认）；全量初始化只补抓无 MANUAL 记录的基金，不覆盖人工修改。

### 4.7 规则版本与重放口径

```
rule_version 语义：
  CN_NAV_SIM_V1_NO_FEE —— 无费口径（历史已确认订单，重放结果必须不变）
  CN_NAV_SIM_V2_FEE   —— 有费口径（新订单、9-18 起重算订单）
```

重放（calculate）天然全量重建：9-17 前 V1 订单按 V1 分支计算，结果与历史 execution 一致；9-18 起订单迁移为 V2 后按新规则确认，daily 曲线、position、ledger 全部重建。

## 5. 接口契约变更

### 5.1 前端可见契约（GET /api/v1/sim-portfolios/current）

- `rules` 文案更新为：`"模拟交易：人民币场外净值基金，沪深开市日日历，15:00 截止，当日净值公布即确认，份额次一交易日可用；申购费与分档赎回费按配置费率模拟，现金分红，先进先出；未模拟渠道限购和临时暂停。"`
- `positions[].execution`（订单列表接口）增加 `fee`/`netAmount`，旧数据为 null，前端空值不展示费用行。

### 5.2 新增管理接口（SimulationFeeController，ADMIN 角色）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | /api/v1/admin/sim-fee-rules | 分页列表，参数 fundCode/feeType/page/pageSize |
| PUT | /api/v1/admin/sim-fee-rules/{ruleId} | 修改费率，version 乐观锁 |
| POST | /api/v1/admin/sim-fee-rules/refresh/{fundCode} | 重新抓取单基金费率 |
| POST | /api/v1/admin/sim-fee-rules/init-all | 全量初始化抓取（跳过 MANUAL 记录） |

权限：复用 system_permission，新增 `SIM_FEE_RULE_ADMIN`，授权 admin 角色。

## 6. 设计决策与取舍

| 决策点 | 方案 | 理由 |
|---|---|---|
| 确认时点判定 | 净值入库（announcedOn<=today）即确认，不等 eligibleDate | 与真实"T 日净值公布后份额当晚到账"一致；用户核心诉求"当晚看到当天收益" |
| 份额可用 | 仍 T+1 交易日（Lot.availableOn） | 与真实"T+1 份额可用"一致，防止净值公布当晚即可卖出的偏差 |
| blockedDate 处理 | 删除净值截断，改为"订单连续确认，估值独立推进" | 订单未确认不再冻结已持有仓位的每日估值 |
| 赎回费分档 | 按 FIFO 批次自然日持有天数 | 行业标准（C 类 7/30 天分档）；批次确认日在 Lot 上可得 |
| 费率数据源 | 天天基金 f10 抓取 + 配置表 + 后台维护 | 用户拍板；Tushare 无费率接口（已核实仅 fund_basic/fund_nav/fund_div） |
| 费率读取 | 结算时全表加载（<1k 行）进内存 Map | 当前规模常量级；预留 version 字段支持将来缓存升级 |
| V1 历史兼容 | 按订单 rule_version 分支重放 | 已确认订单结果不变（用户拍板），无数据翻转风险 |
| 深夜净值 | refresh 窗口加 hour==0 | 覆盖 24:00 前公布的净值，当晚结算 |

## 7. 数据迁移与历史重算（9-18 起）

```sql
-- DML：9-18（含）起的 PENDING 订单升级为 V2 口径，交由结算任务按新规则自动确认重算
UPDATE sim_order
   SET rule_version = 'CN_NAV_SIM_V2_FEE'
 WHERE status = 'PENDING' AND trade_date >= '2026-09-18';
```

- 重算由现有每分钟结算任务自然完成（重放式），无需停服；
- 9-17 及以前 CONFIRMED 订单不迁移，重放走 V1 分支，结果不变；
- sim_daily_valuation 9-18 起由重放覆盖（saveCalculation 已具备净值更正后的旧点清理能力）；
- 若上线后发现问题，回滚：迁移语句反向执行即可，结算任务会用 V1 口径还原（rule_version 是重放口径的唯一依据）。

## 8. 测试策略

- 单元/集成（TDD，SimulationIntegrationTests 新增用例）：
  1. 净值公布即确认：PENDING 定投在净值公布当晚确认，估值推进到最新净值日（复现 9-18 场景）；
  2. 订单未确认时估值不冻结：早期订单净值缺失时，后续日期估值照常；
  3. 申购费：净申购金额与份额计算（费率 0.08% 案例）；
  4. 赎回费分档：<7 天 1.5%、7~30 天 0.5%、≥30 天 0（构造批次确认日与卖出日差）；
  5. 份额可用日：T 日确认份额 T+1 前不可卖（availableShares 与下单校验）；
  6. V1 订单重放不变式：V1 确认订单在新代码下重放结果与历史 execution 一致。
- 手工验证：测试文档见 docs_zhx/textcase/sim-fee-and-nav-settlement.md。

## 9. 风险与兜底

| 风险 | 兜底 |
|---|---|
| 抓取失败/费率缺失 | 无规则 → 费率 0 + job 告警日志；后台手动维护补录 |
| 天天基金页面结构变化 | 解析失败返回明确错误码，刷新任务记录 FAILED，不影响结算 |
| 费率错误导致收益失真 | rule_version 可回滚（§7）；费率变更只影响新订单与重放，历史 execution 保留证据 |
| 深夜窗口拉取增加上游负载 | 30 分钟限频不变，仅多一个时间窗，QPS 可忽略 |

---

## 变更记录

### 2026-09-19｜初版：费率配置表、净值公布即确认、估值解耦、赎回分档、深夜窗口、V2 重放口径

### 2026-09-19｜补充：抓取与后台维护接口落地、两处设计修订
- **变更背景**：实施阶段对数据源与约束细节做了两处修订，并新增管理接口契约。
- **变更点**：
  1. **数据源确认**：FundArchivesDatas.aspx 与移动端 FundMNFInfo 接口均已失效，改为抓取 f10 费率页 HTML（`fundf10.eastmoney.com/jjfl_XXXXXX.html`，服务端渲染），bs4 解析；Python 侧不落库，由 Java 落 sim_fee_rule；
  2. **min_days 占位**：申购规则 min_days 固定存 0（NULL 不参与唯一约束），保证 ON CONFLICT 幂等更新；V19 迁移仅更新列注释；
  3. **卖出校验重放化**：place() 对 SELL 以重放账本计算可用份额（含批次可用日与冻结），行情暂不可重放时退回快照保守值；
  4. **新增接口**：Python `GET /internal/v1/simulation/fees/{code}`（X-Service-Token 鉴权，解析失败 422/抓取失败 502）；Java `GET/PUT /api/v1/admin/sim-fee-rules`、`POST .../refresh/{fundCode}`、`POST .../init-all`，均校验 SIM_FEE_RULE_ADMIN；
  5. **乐观锁**：人工修改走 version 校验，冲突返回 SIM_FEE_VERSION_CONFLICT；init-all 固定 4 并发抓取，单只失败只记录不中断。
- **影响范围**：SimulationService.place、SimulationFeeService/Controller（新增）、SimulationRepository、SimulationMarketClient、Python eastmoney_fee 服务与路由。
- **新增测试**：集成测试 26 用例（+5 费率管理），单元测试 12 用例，Python 解析单测 7 用例，全部通过。

### 2026-09-19｜收尾：V20 口径切换 DML、卖出校验测试修复、静态检查清零
- **变更背景**：费率方案 2026-09-18 起生效，此前下单尚未结算的订单也应按新口径结算，补一个数据迁移；同时修复卖出校验重放化暴露的真实缺陷并收口质量门禁。
- **变更点**：
  1. **V20 DML 迁移**：`sim_order` 中 `status='PENDING' AND trade_date>=2026-09-18` 的待结算订单 `rule_version` 升级为 `CN_NAV_SIM_V2_FEE`；命中部分索引 `ix_sim_order_pending(trade_date)`，`rule_version<>V2` 条件保证幂等；已结算历史行不动，历史回放口径不漂移（§7 规则的落地补充）；
  2. **卖出校验缺陷修复**：快照 `availableShares` 在两次结算之间存在滞后窗口（9-07 结算后批次 9-08 才可用，9-09 下单仍被拒），place() 改为重放账本计算精确可用份额，行情暂不可重放时回退快照保守值（已在 §变更记录前条第 3 点设计，本条为落地后的测试回归确认）；
  3. **质量门禁收口**：Python ruff 清零（长行拆行 + 测试 HTML 数据行 `# ruff: noqa: E501` 豁免）；Java 全量 149 用例（单元 12 + 集成 26 + 其余 111）BUILD SUCCESS；前端 vue-tsc 与 ESLint（--max-warnings=0）通过。
- **影响范围**：新增 `V20__upgrade_pending_sim_orders_to_fee_rule_v2.sql`；无代码改动。
- **数据库验证**：开发库 Flyway V18/V19/V20 均 success；9-18 起 PENDING 订单 23 只（9-18 的 22 只 + 9-21 的 1 只）全部为 `CN_NAV_SIM_V2_FEE`。
